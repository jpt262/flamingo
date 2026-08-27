package com.flamingo.labeling;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P7 label harvest (§14 / ADR-0007): applies outcome vocabulary to companies/
 * evaluations as events. LABELING ONLY — no predictors, no training, no
 * feature engineering (that design is separately owner-gated).
 */
public class OutcomeLabeler {

    public static final List<String> VOCABULARY = List.of(
            "delinquency_resolved", "severe_dilution", "bankruptcy",
            "shell_transition_indicator", "acquired");

    private final JdbcTemplate jdbc;

    public OutcomeLabeler(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Applies one outcome label; unknown vocabulary fails loudly. */
    public long label(long companyId, Long evaluationId, String outcome,
                      LocalDate observedOn, String note) {
        if (!VOCABULARY.contains(outcome)) {
            throw new IllegalArgumentException(
                    "outcome '" + outcome + "' outside ADR-0007 vocabulary " + VOCABULARY);
        }
        jdbc.update("""
                INSERT INTO outcome_labels (company_id, evaluation_id, outcome, observed_on, note)
                VALUES (?, ?, ?, ?, ?)
                """, companyId, evaluationId, outcome, observedOn, note);
        return jdbc.queryForObject("""
                SELECT id FROM outcome_labels WHERE company_id=? AND outcome=?
                ORDER BY id DESC LIMIT 1
                """, Long.class, companyId, outcome);
    }

    /** Label history per company (feeds future training sets — later, gated). */
    public List<Map<String, Object>> history(long companyId) {
        return jdbc.queryForList("""
                SELECT outcome, observed_on, note FROM outcome_labels
                WHERE company_id = ? ORDER BY observed_on, id
                """, companyId);
    }

    /** Vocabulary distribution — the label-harvest scoreboard. */
    public Map<String, Long> distribution() {
        Map<String, Long> out = new LinkedHashMap<>();
        jdbc.query("SELECT outcome, count(*) FROM outcome_labels GROUP BY outcome ORDER BY outcome",
                (rs, i) -> new String[]{rs.getString(1), String.valueOf(rs.getLong(2))})
             .forEach(pair -> out.put(pair[0], Long.parseLong(pair[1])));
        return out;
    }
}
