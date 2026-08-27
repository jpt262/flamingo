package com.flamingo.trunk.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T-11/T-12: determinism (double-eval identical hash + flag multiset) and
 * pack-v0 reproduction over the T-06 synthetic-issuer fixtures (engine side vs
 * expected_flags). Engine is pure JVM — no DB, runs on every build.
 */
@Tag("db")
class GapRuleEngineTest {

    static GapRuleEngine engine;

    @BeforeAll
    static void load() {
        TagDictionaryProbe.init();
        engine = new GapRuleEngine(GapRuleEngine.loadDefaultPack(),
                TagDictionaryProbe.loadDefault().canonicalNames().size());
    }

    /** Avoids a compile dependency on tags package internals in tests. */
    static final class TagDictionaryProbe {
        static com.flamingo.trunk.tags.TagDictionary loadDefault() {
            return com.flamingo.trunk.tags.TagDictionary.loadDefault();
        }
        static void init() {}
    }

    private static List<GapRuleEngine.FlagOutcome> eval(
            List<GapRuleEngine.FilingRef> filings, Map<String, BigDecimal> facts,
            boolean auditor, boolean gc, String asOf) {
        var state = new GapRuleEngine.IssuerEvaluationState(1L, filings, facts,
                auditor, gc, LocalDate.parse(asOf));
        return engine.evaluate(state);
    }

    @Test
    void determinism_doubleEvaluation_identicalHashAndFlagMultiset() {
        var filings = List.of(
                new GapRuleEngine.FilingRef("10-K", LocalDate.parse("2025-05-01")),
                new GapRuleEngine.FilingRef("8-K", LocalDate.parse("2025-11-12")));
        var facts = Map.of("SharesOutstanding", new BigDecimal("1150000000"),
                "SharesAuthorized", new BigDecimal("900000000"));
        var s1 = new GapRuleEngine.IssuerEvaluationState(7L, filings, facts, false, false,
                LocalDate.parse("2026-08-27"));
        var s2 = new GapRuleEngine.IssuerEvaluationState(42L, filings, facts, false, false,
                LocalDate.parse("2026-08-27")); // different companyId, same world

        List<String> f1 = engine.evaluate(s1).stream().map(GapRuleEngine.FlagOutcome::ruleId).sorted().toList();
        List<String> f2 = engine.evaluate(s2).stream().map(GapRuleEngine.FlagOutcome::ruleId).sorted().toList();
        assertThat(f1).isEqualTo(f2);
        assertThat(engine.stateHash(s1)).isEqualTo(engine.stateHash(s2))
                .as("same world state must hash identically regardless of company id");
    }

    @Test
    void packRegistry_isVerbatimFromSpec() {
        var pack = engine.pack();
        assertThat(pack.version()).isEqualTo("v0");
        var disc1 = pack.rules().stream().filter(r -> r.id().equals("DISC-001")).findFirst().orElseThrow();
        assertThat(disc1.citation())
                .isEqualTo("Exchange Act Rule 15c2-11(a): adequate current public information");
        assertThat(disc1.expr()).isEqualTo("days_since_last_form(['10-K','10-Q','20-F','40-F']) > 365");
        var fin1 = pack.rules().stream().filter(r -> r.id().equals("FIN-001")).findFirst().orElseThrow();
        assertThat(fin1.citation())
                .isEqualTo("15c2-11(a)(4)-(6)-adjacent diligence factor");
    }

    // ── T-12: reproduce fixture expected_flags exactly ───────────────────

    @Test
    void packV0ReproducesFixtureExpectedFlags() throws Exception {
        Path dir = Path.of("..", "tests", "concurrence");
        ObjectMapper m = new ObjectMapper();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : files.filter(x -> x.getFileName().toString().matches("issuer-.*\\.json")).toList()) {
                JsonNode f = m.readTree(p.toFile());
                Set<String> expected = new HashSet<>();
                f.path("expected_flags").forEach(n -> expected.add(n.path("rule_id").asText()));

                List<GapRuleEngine.FilingRef> filings = new java.util.ArrayList<>();
                f.path("state").path("filings").forEach(fl ->
                        filings.add(new GapRuleEngine.FilingRef(fl.path("form_type").asText(),
                                LocalDate.parse(fl.path("filed_at").asText()))));
                Map<String, BigDecimal> facts = new java.util.LinkedHashMap<>();
                f.path("state").path("facts").forEach(fa -> {
                    String tag = fa.path("tag").asText();
                    if (tag.equals("EntityCommonStockSharesOutstanding")) {
                        facts.put("SharesOutstanding", fa.path("value").decimalValue());
                    } else if (tag.equals("CommonStockSharesAuthorized")) {
                        facts.put("SharesAuthorized", fa.path("value").decimalValue());
                    } else {
                        facts.putIfAbsent(tag, fa.path("value").decimalValue());
                    }
                });
                boolean auditor = f.path("state").path("signals").path("auditor_on_record").asBoolean();
                boolean gc = f.path("state").path("signals").path("going_concern_language").asBoolean();

                Set<String> engineIds = new HashSet<>();
                eval(filings, facts, auditor, gc, f.path("as_of").asText())
                        .forEach(o -> engineIds.add(o.ruleId()));

                assertThat(engineIds)
                        .as("fixture %s engine-vs-expected", p.getFileName())
                        .containsExactlyInAnyOrderElementsOf(expected);
            }
        }
    }
}
