package com.flamingo.trunk.facts;

import com.fasterxml.jackson.databind.JsonNode;
import com.flamingo.edgar.RawStore;
import com.flamingo.trunk.tags.TagDictionary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * T-07-lite fact extraction over companyfacts-API-shaped JSON.
 *
 * <p>Input shape (live-verified): {@code facts.<ns>.<TAG>.units.<UNIT>[] =
 * {start,end,val,accn,fy,fp,form,filed}}. Facts land under CANONICAL concept
 * names via {@link TagDictionary} candidate maps — within one namespace the
 * first candidate tag PRESENT in the payload wins (ruling §3). Every row binds
 * the caller's evidence_id (R2) and the accession-linked filing (filing_id);
 * rows whose accession is not yet ingested are SKIPPED and counted (never
 * inserted orphaned).</p>
 *
 * <p>R8 supersession: a row reported by a LATER-filed document closes prior
 * open rows for the same (company, canonical tag) — {@code valid_to} = the
 * newer filing's filed date (deterministic, R4), {@code superseded_by} = newer
 * row id. Nothing is ever deleted. Idempotent: an identical (company, tag,
 * accn, period, value) row is detected and not duplicated.</p>
 */
public class FactExtractionService {

    public static final List<String> NAMESPACES =
            List.of(TagDictionary.US_GAAP, TagDictionary.IFRS_FULL, TagDictionary.DEI);

    private final JdbcTemplate jdbc;
    private final TagDictionary dictionary;

    public FactExtractionService(JdbcTemplate jdbc, TagDictionary dictionary) {
        this.jdbc = jdbc;
        this.dictionary = dictionary;
    }

    /** One ingested fact row (post-canonicalization). */
    public record FactRow(long companyId, String taxonomy, String canonicalTag,
                          BigDecimal value, String unit,
                          LocalDate periodStart, LocalDate periodEnd, boolean instant,
                          Integer fy, String fp, String accn, LocalDate filedAt,
                          long evidenceId) {}

    /**
     * @param companyfacts  companyfacts-API-shaped document
     * @param companyId     trunk companies.id
     * @param evidenceId    evidence_refs.id of the deriving snapshot (R2: mandatory)
     * @param derivingSnapshot stored-bytes metadata for the result report (nullable in tests)
     * @return {extracted, skipped_unbound, history_closed, by_concept, raw_object_key, raw_sha256}
     */
    public Map<String, Object> extractFrom(JsonNode companyfacts, long companyId,
                                           long evidenceId, RawStore.Stored derivingSnapshot) {
        int extracted = 0;
        int skippedUnbound = 0;
        int closed = 0;
        Map<String, Integer> byConcept = new LinkedHashMap<>();

        for (String conceptName : dictionary.canonicalNames()) {
            for (String ns : NAMESPACES) {
                int[] c = extractConceptNamespace(conceptName, ns, companyfacts,
                        companyId, evidenceId);
                extracted += c[0];
                skippedUnbound += c[1];
                closed += c[2];
                if (c[0] > 0) {
                    byConcept.merge(conceptName, c[0], Integer::sum);
                }
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("extracted", extracted);
        out.put("skipped_unbound", skippedUnbound);
        out.put("history_closed", closed);
        out.put("by_concept", byConcept);
        if (derivingSnapshot != null) {
            out.put("raw_object_key", derivingSnapshot.objectKey());
            out.put("raw_sha256", derivingSnapshot.sha256());
        }
        return out;
    }

    /**
     * One (concept, namespace) pass: first candidate tag present in the payload
     * wins; all its unit rows are extracted. Returns {extracted, skipped, closed}.
     */
    private int[] extractConceptNamespace(String conceptName, String ns,
                                          JsonNode companyfacts, long companyId,
                                          long evidenceId) {
        for (String rawTag : dictionary.candidatesFor(conceptName, ns)) {
            JsonNode units = companyfacts.path("facts").path(ns).path(rawTag).path("units");
            if (!units.isObject() || units.isEmpty()) {
                continue;
            }
            int extracted = 0;
            int skipped = 0;
            int closed = 0;
            for (var unitEntry : units.properties()) {
                for (JsonNode row : unitEntry.getValue()) {
                    FactRow r = toRow(conceptName, ns, unitEntry.getKey(), row,
                            companyId, evidenceId);
                    if (r == null) {
                        continue;
                    }
                    if (!filingExists(r.accn())) {
                        skipped++;
                        continue;
                    }
                    if (rowAlreadyStored(r)) {
                        continue; // idempotent re-run
                    }
                    insert(r);
                    extracted++;
                    closed += closeSuperseded(r);
                }
            }
            return new int[]{extracted, skipped, closed};
        }
        return new int[]{0, 0, 0};
    }

    private FactRow toRow(String canonical, String ns, String unit, JsonNode row,
                          long companyId, long evidenceId) {
        JsonNode val = row.path("val");
        if (!val.isNumber()) {
            return null;
        }
        boolean instant = !row.hasNonNull("start");
        LocalDate end = parseDate(row.path("end").asText(null));
        if (end == null) {
            return null;
        }
        String accn = row.path("accn").asText(null);
        if (accn == null || accn.isBlank()) {
            return null;
        }
        return new FactRow(companyId, ns, canonical,
                val.decimalValue(), unit,
                instant ? null : parseDate(row.path("start").asText(null)),
                end, instant,
                row.path("fy").isInt() ? row.path("fy").asInt() : null,
                row.path("fp").asText(null),
                accn,
                parseDate(row.path("filed").asText(null)),
                evidenceId);
    }

    private boolean filingExists(String accn) {
        if (accn == null) {
            return false;
        }
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM filings WHERE accession = ?)",
                Boolean.class, accn);
        return Boolean.TRUE.equals(exists);
    }

    private boolean rowAlreadyStored(FactRow r) {
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM facts WHERE company_id=? AND tag=? AND xbrl_ref=?
                        AND unit=? AND period_end IS NOT DISTINCT FROM ?)
                """, Boolean.class,
                r.companyId(), r.canonicalTag(), r.accn(), r.unit(), r.periodEnd());
        return Boolean.TRUE.equals(exists);
    }

    private void insert(FactRow r) {
        jdbc.update("""
                INSERT INTO facts (filing_id, company_id, taxonomy, tag, value, unit,
                                   period_start, period_end, instant, fy, fp, xbrl_ref, evidence_id)
                VALUES ((SELECT id FROM filings WHERE accession = ?), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                r.accn(), r.companyId(), r.taxonomy(), r.canonicalTag(), r.value(),
                r.unit(), r.periodStart(), r.periodEnd(), r.instant(), r.fy(), r.fp(),
                r.accn(), r.evidenceId());
    }

    /** R8 closure — deterministic valid_to = superseding filing's filed date. */
    private int closeSuperseded(FactRow r) {
        if (r.filedAt() == null) {
            return 0;
        }
        Long newId = jdbc.queryForObject("""
                SELECT id FROM facts WHERE company_id=? AND tag=? AND xbrl_ref=? AND unit=?
                        AND period_end IS NOT DISTINCT FROM ?
                ORDER BY id DESC LIMIT 1
                """, Long.class,
                r.companyId(), r.canonicalTag(), r.accn(), r.unit(), r.periodEnd());
        if (newId == null) {
            return 0;
        }
        return jdbc.update("""
                UPDATE facts SET valid_to = ?, superseded_by = ?
                WHERE company_id = ? AND tag = ? AND id <> ? AND valid_to IS NULL
                  AND COALESCE(period_end, period_start) < ?
                """,
                java.sql.Timestamp.valueOf(r.filedAt().atStartOfDay()),
                newId, r.companyId(), r.canonicalTag(), newId, r.periodEnd());
    }

    private static LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s);
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }
}
