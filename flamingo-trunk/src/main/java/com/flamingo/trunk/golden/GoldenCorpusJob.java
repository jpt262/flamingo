package com.flamingo.trunk.golden;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flamingo.edgar.EdgarClient;
import com.flamingo.edgar.RawStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * T-05 golden corpus builder (build order §16): recent 424B5s via month-window
 * FTS union → hashed index, RERUNNABLE (existing verified entries skipped,
 * interrupted runs resume). Raw document bytes live exclusively in the R1
 * store; {@code tests/golden/index.json} commits accession → integrity binding.
 *
 * <p>Resolution rule proven live (2026-08-27): an FTS hit's {@code adsh} prefix
 * is the FILED-BY agent CIK, which usually does NOT host the documents — the
 * ISSUER CIK comes from {@code _source.ciks[0]} (e.g. Hoth Therapeutics rows
 * carry agent prefix 0001213900 but archive under 0001711786). Probing the
 * agent directory returns index.json 404s en masse.</p>
 */
public class GoldenCorpusJob {

    private final EdgarClient edgar;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Path indexDir;

    public GoldenCorpusJob(EdgarClient edgar, Path indexDir) {
        this.edgar = edgar;
        this.indexDir = indexDir.toAbsolutePath().normalize();
    }

    /** Index row: accession bound to its R1-stored primary document bytes. */
    public record Entry(String accession, int cikInt, String filedAt,
                        String docObjectKey, String docSha256) {}

    public Map<String, Object> run(int targetDocs, List<YearMonth> windowsDesc,
                                   ProgressListener listener) throws IOException {
        Files.createDirectories(indexDir);
        Path indexPath = indexDir.resolve("index.json");

        Map<String, Entry> have = loadIndex(indexPath);

        // Phase A — discovery via disjoint month windows (union semantics).
        // Keep each hit's _source alongside its adsh; resolution needs ciks[].
        Map<String, JsonNode> wanted = new LinkedHashMap<>();
        for (YearMonth ym : windowsDesc) {
            JsonNode fts = edgar.searchIndex(ym.atDay(1).toString(), ym.atEndOfMonth().toString());
            for (JsonNode hit : fts.path("hits").path("hits")) {
                JsonNode src = hit.path("_source");
                String adsh = src.path("adsh").asText("");
                if (!adsh.isBlank()) {
                    wanted.putIfAbsent(adsh, src);
                }
            }
            if (listener != null) {
                listener.windowDone(ym, wanted.size(), have.size());
            }
        }
        if (wanted.isEmpty()) {
            throw new IllegalStateException("FTS returned zero 424B5 rows — cannot build corpus");
        }

        // Phase B — acquisition: skip already-indexed, flush after every success.
        int acquiredThisRun = 0;
        int skippedAlready = 0;
        int failures = 0;
        for (Map.Entry<String, JsonNode> w : wanted.entrySet()) {
            if (have.size() >= targetDocs) {
                break;
            }
            if (have.containsKey(w.getKey())) {
                skippedAlready++;
                continue;
            }
            try {
                Entry e = acquireOne(w.getKey(), w.getValue());
                have.put(e.accession(), e);
                acquiredThisRun++;
                flushIndex(indexPath, have);
                if (listener != null) {
                    listener.docAcquired(e.accession(), have.size(), targetDocs);
                }
            } catch (RuntimeException | IOException ex) {
                failures++;
                if (listener != null) {
                    listener.docFailed(w.getKey(), ex.getMessage());
                }
            }
        }

        writeChecksums(have);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("indexed_total", have.size());
        out.put("acquired_this_run", acquiredThisRun);
        out.put("skipped_already_indexed", skippedAlready);
        out.put("resolve_failures", failures);
        out.put("index_path", indexPath.toString());
        return out;
    }

    /**
     * Resolves the primary document under the ISSUER's Archives directory,
     * persists bytes through the R1 client, returns the integrity commitment.
     */
    private Entry acquireOne(String adsh, JsonNode sourceMeta) throws IOException {
        String nodash = adsh.replace("-", "");
        String issuerCikPadded = sourceMeta.path("ciks").path(0).asText("");
        if (issuerCikPadded.isBlank()) {
            // Rare fallback: hits without ciks[] historically resolve under adsh prefix.
            issuerCikPadded = adsh.split("-")[0];
        }
        int cikInt = Integer.parseInt(issuerCikPadded.replaceFirst("^0+(?!$)", ""));
        String filed = sourceMeta.path("file_date").asText("");

        JsonNode idx = edgar.filingIndex(cikInt, nodash);

        // Primary-document selector: authoritative type=424B5 wins immediately;
        // otherwise first non-XSL .htm entry.
        String primary = null;
        boolean sawXslWrapper = false;
        for (JsonNode item : idx.path("directory").path("item")) {
            String name = item.path("name").asText("");
            String type = item.path("type").asText("");
            if ("424B5".equalsIgnoreCase(type)) {
                primary = name;
                break;
            }
            if (primary == null && name.toLowerCase().endsWith(".htm")
                    && !name.contains("xsl")) {
                primary = name;
            }
            if (name.contains("xsl")) {
                sawXslWrapper = true;
            }
        }
        if (primary == null) {
            throw new IllegalStateException("no primary document in " + adsh
                    + " (xsl-only=" + sawXslWrapper + ")");
        }

        String docUrl = "https://www.sec.gov/Archives/edgar/data/" + cikInt
                + "/" + nodash + "/" + primary;
        RawStore.Stored stored = edgar.fetchRaw(docUrl);
        return new Entry(adsh, cikInt, filed, stored.objectKey(), stored.sha256());
    }

    private Map<String, Entry> loadIndex(Path p) throws IOException {
        Map<String, Entry> out = new LinkedHashMap<>();
        if (Files.exists(p)) {
            JsonNode root = mapper.readTree(p.toFile());
            for (JsonNode n : root.path("filings")) {
                out.put(n.path("accession").asText(),
                        new Entry(n.path("accession").asText(),
                                n.path("cik_int").asInt(),
                                n.path("filed_at").asText(),
                                n.path("doc_object_key").asText(),
                                n.path("doc_sha256").asText()));
            }
        }
        return out;
    }

    private void flushIndex(Path p, Map<String, Entry> entries) throws IOException {
        ObjectNode root = mapper.createObjectNode();
        root.put("form", "424B5");
        root.put("count", entries.size());
        ArrayNode arr = root.putArray("filings");
        for (Entry e : entries.values()) {
            ObjectNode n = arr.addObject();
            n.put("accession", e.accession());
            n.put("cik_int", e.cikInt());
            n.put("filed_at", e.filedAt());
            n.put("doc_object_key", e.docObjectKey());
            n.put("doc_sha256", e.docSha256());
        }
        Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
        mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), root);
        Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private void writeChecksums(Map<String, Entry> entries) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Entry e : entries.values()) {
            sb.append(e.docSha256()).append("  ").append(e.accession()).append('\n');
        }
        Files.writeString(indexDir.resolve("SHA256SUMS"), sb.toString());
    }

    public interface ProgressListener {
        void windowDone(YearMonth window, int discoveredTotal, int haveTotal);
        void docAcquired(String accession, int haveTotal, int target);
        void docFailed(String accession, String reason);
    }
}
