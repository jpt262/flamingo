package com.flamingo.trunk.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * T-11/T-12 gap rule engine over IssuerEvaluationState.
 *
 * <p>Rules are DATA: pack {@code flamingo-rules/pack-v0.yaml} carries id,
 * dimension, severity, citation (rendered VERBATIM into flags — never
 * paraphrased), remediation, and the expr string (audited, stored in observed).
 * Predicates resolve through a built-in evaluator registry keyed by rule id;
 * a pack rule without a registered evaluator fails LOUDLY (never silently
 * skipped). Deterministic: same state ⇒ same state_hash ⇒ same flag multiset
 * (R4); {@code as_of} is injectable (fixtures pin it).</p>
 */
public class GapRuleEngine {

    public record RuleDef(String id, String dimension, String severity,
                          String expr, String citation, String remediation) {}

    public record RulePack(String version, List<RuleDef> rules) {}

    public record FilingRef(String formType, LocalDate filedAt) {}

    /** Immutable evaluation input. facts = canonical concept → latest value. */
    public record IssuerEvaluationState(
            long companyId, List<FilingRef> filings, Map<String, BigDecimal> facts,
            boolean auditorOnRecord, boolean goingConcern, LocalDate asOf) {}

    public record FlagOutcome(String ruleId, String dimension, String severity,
                              Map<String, Object> observed, String citation,
                              String remediation, String expr) {}

    private static final Set<String> PERIODIC_FORMS = Set.of("10-K", "10-Q", "20-F", "40-F");
    private static final double XBRL_COVERAGE_FLOOR = 0.60;
    private static final long DELINQUENCY_DAYS = 365;

    private final RulePack pack;
    private final int dictionaryConceptCount;

    public GapRuleEngine(RulePack pack, int dictionaryConceptCount) {
        this.pack = pack;
        this.dictionaryConceptCount = dictionaryConceptCount;
    }

    public static RulePack loadDefaultPack() {
        String res = "/flamingo-rules/pack-v0.yaml";
        try (InputStream in = GapRuleEngine.class.getResourceAsStream(res)) {
            if (in == null) {
                throw new IllegalStateException("missing classpath resource " + res);
            }
            ObjectMapper y = new ObjectMapper(new YAMLFactory());
            JsonNode root = y.readTree(in);
            List<RuleDef> rules = new ArrayList<>();
            for (JsonNode r : root.path("rules")) {
                rules.add(new RuleDef(r.path("id").asText(), r.path("dimension").asText(),
                        r.path("severity").asText(), r.path("expr").asText(),
                        r.path("citation").asText(), r.path("remediation").asText()));
            }
            if (rules.isEmpty()) {
                throw new IllegalStateException("rule pack empty — refusing to start");
            }
            return new RulePack(root.path("pack_version").asText("v0"), List.copyOf(rules));
        } catch (IOException e) {
            throw new IllegalStateException("cannot load rule pack", e);
        }
    }

    /** Pure evaluation: no DB, fully deterministic. */
    public List<FlagOutcome> evaluate(IssuerEvaluationState s) {
        List<FlagOutcome> flags = new ArrayList<>();
        for (RuleDef rule : pack.rules()) {
            Map<String, Object> observed = switch (rule.id()) {
                case "DISC-001" -> evalDisc001(s);
                case "DISC-002" -> evalDisc002(s);
                case "FIN-001" -> evalFin001(s);
                case "FIN-002" -> evalFin002(s);
                default -> throw new IllegalStateException(
                        "rule " + rule.id() + " has no registered evaluator — loud fail");
            };
            if (observed != null) {
                Map<String, Object> withExpr = new LinkedHashMap<>(observed);
                withExpr.put("expr", rule.expr());
                flags.add(new FlagOutcome(rule.id(), rule.dimension(), rule.severity(),
                        withExpr, rule.citation(), rule.remediation(), rule.expr()));
            }
        }
        return List.copyOf(flags);
    }

    /** Deterministic sha256 of the canonical (sorted-keys) state serialization. */
    public String stateHash(IssuerEvaluationState s) {
        try {
            TreeMap<String, Object> canonical = new TreeMap<>();
            canonical.put("as_of", s.asOf().toString());
            canonical.put("auditor_on_record", s.auditorOnRecord());
            canonical.put("going_concern", s.goingConcern());
            TreeMap<String, String> facts = new TreeMap<>();
            s.facts().forEach((k, v) -> facts.put(k, v.toPlainString()));
            canonical.put("facts", facts);
            List<String> filings = s.filings().stream()
                    .map(f -> f.formType() + "@" + f.filedAt())
                    .sorted().toList();
            canonical.put("filings", filings);
            // company_id excluded: same world state must hash identically across companies
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest(canonical.toString().getBytes(StandardCharsets.UTF_8))) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("state hash failed", e);
        }
    }

    public RulePack pack() {
        return pack;
    }

    // ── predicates ──────────────────────────────────────────────────────

    private Map<String, Object> evalDisc001(IssuerEvaluationState s) {
        LocalDate latest = s.filings().stream()
                .filter(f -> PERIODIC_FORMS.contains(f.formType().toUpperCase()))
                .map(FilingRef::filedAt)
                .max(LocalDate::compareTo)
                .orElse(null);
        if (latest == null) {
            return Map.of("days_since", Long.MAX_VALUE, "form", "(none on record)");
        }
        long days = ChronoUnit.DAYS.between(latest, s.asOf());
        return days > DELINQUENCY_DAYS
                ? Map.of("days_since", days, "form", "latest periodic filing")
                : null;
    }

    private Map<String, Object> evalDisc002(IssuerEvaluationState s) {
        if (dictionaryConceptCount == 0) {
            return null;
        }
        double coverage = (double) s.facts().size() / dictionaryConceptCount;
        return coverage < XBRL_COVERAGE_FLOOR
                ? Map.of("coverage", Math.round(coverage * 1000) / 1000.0,
                         "present", s.facts().size(), "total", dictionaryConceptCount)
                : null;
    }

    private Map<String, Object> evalFin001(IssuerEvaluationState s) {
        return (!s.auditorOnRecord() || s.goingConcern())
                ? Map.of("auditor_on_record", s.auditorOnRecord(),
                         "going_concern_language", s.goingConcern())
                : null;
    }

    private Map<String, Object> evalFin002(IssuerEvaluationState s) {
        BigDecimal outstanding = s.facts().get("SharesOutstanding");
        BigDecimal authorized = s.facts().get("SharesAuthorized");
        if (outstanding == null || authorized == null) {
            return null; // cannot evaluate without both sides — no fabricated flag
        }
        return outstanding.compareTo(authorized) > 0
                ? Map.of("shares_outstanding", outstanding.toPlainString(),
                         "authorized_shares", authorized.toPlainString())
                : null;
    }
}
