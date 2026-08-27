package com.flamingo.trunk.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.flamingo.edgar.EdgarClient;
import com.flamingo.edgar.RawStore;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ingest contract §7: ingest.ensure_filings(cik) → filing ids.
 *
 * <p>Write semantics follow STACK RULING §4 (supersession regime): filings are
 * append-only — ON CONFLICT (accession) DO NOTHING. Idempotent re-runs are
 * no-ops; restated metadata never overwrites history.</p>
 *
 * <p>Provenance binding (R1/R2): every filings row carries the object key and
 * sha256 of the SUBMISSIONS FEED SNAPSHOT it was derived from — real stored
 * bytes returned by the R1 client. When T-05 binds per-document artifacts,
 * those arrive as their own evidence_refs rows; this column keeps meaning
 * "hash of the exact response this row came from" forever.</p>
 */
public class EnsureFilingsService {

    private final EdgarClient edgar;
    private final JdbcTemplate jdbc;

    public EnsureFilingsService(EdgarClient edgar, JdbcTemplate jdbc) {
        this.edgar = edgar;
        this.jdbc = jdbc;
    }

    /** Live path: fetch (client persists raw first), then land rows bound to those bytes. */
    public Map<String, Object> ensureFilings(String cik10, String entityName) {
        long companyId = ensureCompany(cik10, entityName);
        EdgarClient.Fetched fetched = edgar.submissionsStored(cik10);
        return ensureFilingsFrom(companyId, fetched.doc(), fetched.stored());
    }

    /** Offline/testable path over an already-fetched document + its stored-bytes metadata. */
    public Map<String, Object> ensureFilingsFrom(long companyId, JsonNode submissionsDoc,
                                                 RawStore.Stored derivingSnapshot) {
        JsonNode recent = submissionsDoc.path("filings").path("recent");
        int n = recent.path("accessionNumber").size();

        List<Long> ids = new ArrayList<>();
        int inserted = 0;
        for (int i = 0; i < n; i++) {
            String accession = text(recent, "accessionNumber", i);
            if (accession == null || accession.isBlank()) {
                continue;
            }
            String formType = text(recent, "form", i);
            String filedAt = text(recent, "filingDate", i);
            String periodOfReport = text(recent, "reportDate", i);
            // SEC feed reality: empty strings, not JSON nulls (live-verified).
            if (periodOfReport != null && periodOfReport.isBlank()) {
                periodOfReport = null;
            }
            if (filedAt == null || filedAt.isBlank()
                    || formType == null || formType.isBlank()) {
                continue;
            }

            // Supersession regime: plain INSERT, conflict ⇒ DO NOTHING (ruling Q1).
            String sql = "INSERT INTO filings (company_id, accession, form_type, filed_at,"
                    + " period_of_report, raw_object_key, raw_sha256)"
                    + " VALUES (?, ?, ?, ?::timestamptz, "
                    + (periodOfReport == null ? "NULL" : "?::date") + ", ?, ?)"
                    + " ON CONFLICT (accession) DO NOTHING";

            List<Object> params = new ArrayList<>(List.of(companyId, accession, formType, filedAt));
            if (periodOfReport != null) {
                params.add(periodOfReport);
            }
            params.add(derivingSnapshot.objectKey());
            params.add(derivingSnapshot.sha256());

            int updated = jdbc.update(sql, params.toArray());
            if (updated > 0) {
                inserted++;
                ids.add(jdbc.queryForObject(
                        "SELECT id FROM filings WHERE accession = ?", Long.class, accession));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("company_id", companyId);
        out.put("seen", n);
        out.put("inserted", inserted);
        out.put("raw_object_key", derivingSnapshot.objectKey());
        out.put("raw_sha256", derivingSnapshot.sha256());
        out.put("filing_ids", ids);
        return out;
    }

    public long ensureCompany(String cik10, String entityName) {
        jdbc.update("""
                INSERT INTO companies (cik, entity_name) VALUES (?, ?)
                ON CONFLICT (cik) DO NOTHING
                """, cik10, entityName == null ? "(unknown)" : entityName);
        return jdbc.queryForObject("SELECT id FROM companies WHERE cik = ?", Long.class, cik10);
    }

    private static String text(JsonNode recent, String key, int idx) {
        JsonNode v = recent.path(key).get(idx);
        return v == null || v.isNull() ? null : v.asText();
    }
}
