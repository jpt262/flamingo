package com.flamingo.trunk.rules;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

/**
 * Persists engine output: evaluations row (rule_pack_version + state_hash) and
 * gap_flags rows with observed JSONB + citation VERBATIM from the pack.
 * Append-only (R8): evaluations accumulate; dispositions change via workflow,
 * never by editing rows.
 */
public class EvaluationWriter {

    private final JdbcTemplate jdbc;

    public EvaluationWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long persist(long companyId, GapRuleEngine engine,
                        GapRuleEngine.IssuerEvaluationState state,
                        List<GapRuleEngine.FlagOutcome> flags,
                        long[] evidenceIds) {
        String hash = engine.stateHash(state);
        jdbc.update("""
                INSERT INTO evaluations (company_id, rule_pack_version, state_hash)
                VALUES (?, ?, ?)
                """, companyId, engine.pack().version(), hash);
        Long evalId = jdbc.queryForObject(
                "SELECT id FROM evaluations WHERE company_id=? AND state_hash=?"
                        + " ORDER BY id DESC LIMIT 1",
                Long.class, companyId, hash);

        for (GapRuleEngine.FlagOutcome f : flags) {
            jdbc.update("""
                    INSERT INTO gap_flags (evaluation_id, company_id, rule_id, dimension,
                                           severity, observed, citation, remediation, evidence_ids)
                    VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                    """,
                    evalId, companyId, f.ruleId(), f.dimension(), f.severity(),
                    toSortedJson(f.observed()), f.citation(), f.remediation(),
                    evidenceIds);
        }
        return evalId;
    }

    /** Sorted-keys JSON → byte-stable across runs (R4). */
    private static String toSortedJson(Map<String, Object> observed) {
        java.util.TreeMap<String, Object> sorted = new java.util.TreeMap<>(observed);
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (var e : sorted.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(e.getKey()).append("\":");
            Object v = e.getValue();
            if (v instanceof String s) {
                sb.append('"').append(s.replace("\"", "\\\"")).append('"');
            } else {
                sb.append(v);
            }
        }
        return sb.append('}').toString();
    }
}
