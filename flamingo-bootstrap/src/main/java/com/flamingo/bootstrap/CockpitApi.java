package com.flamingo.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.flamingo.trunk.evidence.ManifestWriter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Operator cockpit API (loom-style): thin read-only JSON over live trunk state.
 * Serves the zero-dependency static cockpit at {@code /index.html}.
 */
@RestController
@RequestMapping("/api")
public class CockpitApi {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public CockpitApi(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One-call health: counts, chain verdict, raw-store footprint, golden progress. */
    @GetMapping("/status")
    public Object status() {
        var root = mapper.createObjectNode();
        root.put("companies", count("SELECT count(*) FROM companies"));
        root.put("filings", count("SELECT count(*) FROM filings"));
        root.put("evidence_refs", count("SELECT count(*) FROM evidence_refs"));
        root.put("facts", count("SELECT count(*) FROM facts"));
        root.put("manifests", count("SELECT count(*) FROM manifests"));

        List<Long> broken = new ManifestWriter(jdbc).verifyChain();
        root.put("chain", broken.isEmpty() ? "SOUND" : "BROKEN:" + broken);

        root.set("rawstore", rawStoreNode());
        root.set("golden", goldenNode());
        return root;
    }

    /** Recent filings feed (newest first) for the cockpit center column. */
    @GetMapping("/filings")
    public ArrayNode recentFilings() {
        ArrayNode arr = mapper.createArrayNode();
        jdbc.query("""
                SELECT f.accession, f.form_type, f.filed_at, f.raw_sha256,
                       c.cik, c.entity_name
                FROM filings f JOIN companies c ON c.id = f.company_id
                ORDER BY f.filed_at DESC LIMIT 25
                """, rs -> {
            var n = arr.addObject();
            n.put("accession", rs.getString(1));
            n.put("form", rs.getString(2));
            n.put("filed", String.valueOf(rs.getTimestamp(3)));
            n.put("sha256", rs.getString(4));
            n.put("cik", rs.getString(5));
            n.put("entity", rs.getString(6));
        });
        return arr;
    }

    private int count(String sql) {
        Integer v = jdbc.queryForObject(sql, Integer.class);
        return v == null ? -1 : v;
    }

    private com.fasterxml.jackson.databind.node.ObjectNode rawStoreNode() {
        var n = mapper.createObjectNode();
        Path store = Path.of(
                System.getProperty("flamingo.raw-store", ".data/rawstore"));
        long files = 0;
        try (var walk = Files.walk(store)) {
            files = walk.filter(Files::isRegularFile).count();
        } catch (IOException ignored) {
            // store absent until first LIVE run — cockpit shows 0
        }
        n.put("evidence_files", files);
        n.put("root", store.toString());
        return n;
    }

    private com.fasterxml.jackson.databind.node.ObjectNode goldenNode() {
        var n = mapper.createObjectNode();
        Path idx = Path.of("tests/golden/index.json");
        n.put("indexed", 0);
        try {
            var tree = mapper.readTree(idx.toFile());
            n.put("indexed", tree.path("count").asInt(0));
            n.put("target_hint", 200);
        } catch (IOException ignored) {
            // corpus not built yet
        }
        return n;
    }
}
