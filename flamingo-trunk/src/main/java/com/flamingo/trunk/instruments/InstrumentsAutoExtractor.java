package com.flamingo.trunk.instruments;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T-10 instruments auto-extraction from exhibit TEXT SNIPPETS (synthetic-exhibit
 * scope; real exhibit-document parsing is a later ticket). Candidates land as
 * {@code needs_confirmation} rows in the instruments queue with citations and a
 * confidence field — humans confirm via portal later (spec §7).
 *
 * <p>Never fabricates: unparseable/ambiguous snippets yield zero rows.</p>
 */
public class InstrumentsAutoExtractor {

    public record Extraction(String kind, ObjectNode terms, String citation) {}

    private static final Pattern CONVERTIBLE = Pattern.compile(
            "(?i)convertible\\s+(?:senior\\s+|subordinated\\s+)*note[s]?\\s*[^.]{0,200}?"
            + "(?:aggregate principal(?:\\s+amount)?\\s+of\\s+\\$([\\d,]+(?:\\.\\d+)?)"
            + "|principal\\s+amount\\s+of\\s+\\$([\\d,]+(?:\\.\\d+)?))"
            + "[^.]{0,200}?(?:conversion\\s+price\\s+of\\s+\\$([\\d.]+)"
            + "|convertible\\s+at\\s+\\$([\\d.]+)"
            + "|conversion\\s+ratio\\s+of\\s+([\\d.]+)\\s*shares?)");
    private static final Pattern WARRANT = Pattern.compile(
            "(?i)warrant[s]?\\s+(?:to\\s+purchase\\s+)?([\\d,]+)\\s+shares?"
            + "[^.]{0,200}?(?:exercise\\s+price\\s+of\\s+\\$([\\d.]+)"
            + "|at\\s+an\\s+exercise\\s+price\\s+of\\s+\\$([\\d.]+))"
            + "(?:[^.]{0,150}?expiring\\s+([A-Z][a-z]+\\s+\\d{1,2},\\s*\\d{4}))?");

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public InstrumentsAutoExtractor(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Extracts candidates from one snippet; inserts rows; returns what landed. */
    public List<Extraction> extractAndQueue(long companyId, long evidenceId,
                                            String exhibitSnippet) {
        List<Extraction> out = new ArrayList<>();
        if (exhibitSnippet == null || exhibitSnippet.isBlank()) {
            return out;
        }
        Matcher m = CONVERTIBLE.matcher(exhibitSnippet);
        if (m.find()) {
            String principal = firstNonNullOrNull(m.group(1), m.group(2));
            String convPrice = firstNonNullOrNull(m.group(3), m.group(4));
            String ratio = m.group(5);
            if (principal != null && (convPrice != null || ratio != null)) {
                ObjectNode terms = mapper.createObjectNode();
                terms.put("principal_amount", new BigDecimal(principal.replace(",", "")));
                if (convPrice != null) {
                    terms.put("conversion_price", new BigDecimal(convPrice));
                }
                if (ratio != null) {
                    terms.put("conversion_ratio", new BigDecimal(ratio));
                }
                terms.put("confidence", 0.8);
                out.add(new Extraction("convertible_note", terms,
                        snippetCitation(exhibitSnippet, m.start(), m.end())));
            }
        }
        m = WARRANT.matcher(exhibitSnippet);
        if (m.find()) {
            String shares = m.group(1);
            String price = firstNonNullOrNull(m.group(2), m.group(3));
            String expiry = m.group(4);
            if (shares != null && price != null) {
                ObjectNode terms = mapper.createObjectNode();
                terms.put("shares", new BigDecimal(shares.replace(",", "")));
                terms.put("exercise_price", new BigDecimal(price));
                if (expiry != null) {
                    terms.put("expiration", expiry);
                }
                terms.put("confidence", 0.75);
                out.add(new Extraction("warrant", terms,
                        snippetCitation(exhibitSnippet, m.start(), m.end())));
            }
        }
        for (Extraction e : out) {
            jdbc.update("""
                    INSERT INTO instruments (company_id, kind, terms, extraction_status,
                                             evidence_id)
                    VALUES (?, ?, ?::jsonb, 'needs_confirmation', ?)
                    """,
                    companyId, e.kind(), e.terms().toString(), evidenceId);
        }
        return out;
    }

    public Map<String, Object> queueStats(long companyId) {
        Map<String, Object> out = new LinkedHashMap<>();
        jdbc.query("""
                SELECT kind, count(*) FROM instruments
                WHERE company_id = ? AND extraction_status = 'needs_confirmation'
                GROUP BY kind ORDER BY kind
                """, rs -> {
            out.put(rs.getString(1), rs.getInt(2));
        }, companyId);
        return out;
    }

    private static String firstNonNullOrNull(String a, String b) {
        return a != null ? a : b;
    }

    private static String snippetCitation(String snippet, int start, int end) {
        int lo = Math.max(0, start - 40);
        int hi = Math.min(snippet.length(), end + 40);
        return "exhibit-snippet[" + lo + ":" + hi + "] \"…"
                + snippet.substring(lo, hi).replaceAll("\\s+", " ") + "…\"";
    }
}
