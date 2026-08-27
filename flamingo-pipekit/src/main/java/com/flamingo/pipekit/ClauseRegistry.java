package com.flamingo.pipekit;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P6b PIPE Kit (§13b): clause registry + precedent assembly for counsel seats.
 * Clause CONTENT is counsel-review-gated (counsel_review column); this service
 * manages the registry and assembles ordered outlines — it never renders
 * unreviewed clauses into final work product.
 */
public class ClauseRegistry {

    private final JdbcTemplate jdbc;

    public ClauseRegistry(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long register(String clauseKey, String category, String title,
                         String body, String counselReview) {
        jdbc.update("""
                INSERT INTO pipe_clauses (clause_key, category, title, body, counsel_review)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (clause_key) DO NOTHING
                """, clauseKey, category, title, body,
                counselReview == null ? "pending" : counselReview);
        return jdbc.queryForObject(
                "SELECT id FROM pipe_clauses WHERE clause_key = ?", Long.class, clauseKey);
    }

    public record Clause(long id, String clauseKey, String category, String title,
                         String counselReview) {}

    /** Registry listing; optionally filtered to reviewed-only for assembly. */
    public List<Clause> list(String category, boolean approvedOnly) {
        return jdbc.query("""
                SELECT id, clause_key, category, title, counsel_review
                FROM pipe_clauses
                WHERE (?::text IS NULL OR category = ?)
                  AND (? = false OR counsel_review = 'approved')
                ORDER BY category, title
                """, (rs, i) -> new Clause(rs.getLong(1), rs.getString(2),
                        rs.getString(3), rs.getString(4), rs.getString(5)),
                category, category, approvedOnly);
    }

    /** Assembly outline: ordered clause keys, annotated with review state. */
    public Map<String, Object> assemble(String name, List<String> clauseKeys) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("assembly", name);
        List<Map<String, Object>> items = new java.util.ArrayList<>();
        boolean allApproved = true;
        for (String key : clauseKeys) {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT clause_key, category, title, counsel_review FROM pipe_clauses "
                            + "WHERE clause_key = ?", key);
            if (rows.isEmpty()) {
                throw new IllegalArgumentException("unknown clause: " + key);
            }
            Map<String, Object> row = rows.get(0);
            if (!"approved".equals(row.get("counsel_review"))) {
                allApproved = false;
            }
            items.add(row);
        }
        out.put("items", items);
        out.put("production_ready", allApproved); // false while any clause pending review
        return out;
    }
}
