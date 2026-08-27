package com.flamingo.trunk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T-06 harness: validates every tests/concurrence/issuer-*.json fixture against
 * the v1 format contract and the pack-v0 rule registry (build order §16).
 * Offline, deterministic, runs on every build.
 */
class TrunkConcurrenceFixtureTest {

    /** Pack v0 registry (spec §7): rule_id → (dimension, severity). */
    private static final Map<String, String> REGISTRY_DIMENSION = Map.of(
            "DISC-001", "disclosure_timeliness",
            "DISC-002", "xbrl_completeness",
            "FIN-001", "auditor_presence",
            "FIN-002", "capital_sanity");
    private static final Map<String, String> REGISTRY_SEVERITY = Map.of(
            "DISC-001", "blocking",
            "DISC-002", "warn",
            "FIN-001", "blocking",
            "FIN-002", "warn");
    private static final Set<String> SEVERITIES = Set.of("blocking", "warn", "info");

    private final ObjectMapper mapper = new ObjectMapper();

    private Path fixturesDir() {
        // surefire cwd = module basedir; fixtures live at repo-root tests/concurrence
        return Path.of("..", "tests", "concurrence");
    }

    @Test
    void canonicalScenarioTripletIsPresent() throws IOException {
        List<String> names = fixtureFiles().stream().map(p -> p.getFileName().toString()).toList();
        assertThat(names).contains("issuer-current.json",
                "issuer-delinquent-400d.json", "issuer-going-concern.json");
    }

    @TestFactory
    Stream<DynamicTest> everyFixtureValidatesAgainstFormatContract() throws IOException {
        return fixtureFiles().stream().map(p ->
                DynamicTest.dynamicTest(p.getFileName().toString(), () -> validate(p)));
    }

    @Test
    void seededDisagreementExistsSoConcurrenceIsProvableBelow100Pct() throws IOException {
        JsonNode delinquent = mapper.readTree(
                fixturesDir().resolve("issuer-delinquent-400d.json").toFile());
        List<String> expected = idsOf(delinquent.path("expected_flags"));
        List<String> analyst = idsOf(delinquent.path("analyst_judgment").path("flags"));
        assertThat(expected).contains("DISC-001", "FIN-002");
        assertThat(analyst).contains("DISC-001").doesNotContain("FIN-002");
    }

    @Test
    void currentIssuerIsATrueNegative() throws IOException {
        JsonNode current = mapper.readTree(
                fixturesDir().resolve("issuer-current.json").toFile());
        assertThat(idsOf(current.path("expected_flags"))).isEmpty();
        assertThat(idsOf(current.path("analyst_judgment").path("flags"))).isEmpty();
    }

    // ── per-fixture validation ──────────────────────────────────────────

    private void validate(Path p) throws IOException {
        JsonNode f = mapper.readTree(p.toFile());
        String name = p.getFileName().toString();

        assertThat(f.path("fixture_version").asInt()).as(name + ": version").isEqualTo(1);
        assertThat(f.path("scenario").asText()).as(name + ": scenario").isNotBlank();
        assertThat(isIsoDate(f.path("as_of").asText(null))).as(name + ": as_of").isTrue();

        JsonNode issuer = f.path("issuer");
        assertThat(issuer.path("cik").asText()).as(name + ": cik").matches("\\d{10}");
        assertThat(issuer.path("entity_name").asText()).as(name + ": entity").isNotBlank();

        for (JsonNode fil : f.path("state").path("filings")) {
            assertThat(fil.path("accession").asText()).as(name + ": accession")
                    .matches("\\d{10}-\\d{2}-\\d{6}");
            assertThat(fil.path("form_type").asText()).as(name + ": form").isNotBlank();
            assertThat(isIsoDate(fil.path("filed_at").asText(null))).as(name + ": filed_at").isTrue();
        }
        for (JsonNode fact : f.path("state").path("facts")) {
            assertThat(fact.path("taxonomy").asText()).as(name + ": taxonomy").isIn("dei", "us-gaap");
            assertThat(fact.path("tag").asText()).as(name + ": tag").isNotBlank();
            assertThat(fact.path("unit").asText()).as(name + ": unit").isIn("shares", "USD");
            assertThat(fact.has("value")).as(name + ": value present").isTrue();
        }
        JsonNode signals = f.path("state").path("signals");
        assertThat(signals.path("auditor_on_record").isBoolean()).as(name + ": auditor signal").isTrue();
        assertThat(signals.path("going_concern_language").isBoolean()).as(name + ": gc signal").isTrue();

        for (JsonNode flag : f.path("expected_flags")) {
            assertRegistryMember(flag, name + ": expected", true);
        }
        for (JsonNode flag : f.path("analyst_judgment").path("flags")) {
            assertRegistryMember(flag, name + ": analyst", false);
        }
        assertThat(f.path("analyst_judgment").path("analyst").asText()).as(name + ": analyst id").isNotBlank();
    }

    private void assertRegistryMember(JsonNode flag, String where, boolean requireDimension) {
        String ruleId = flag.path("rule_id").asText("");
        assertThat(REGISTRY_DIMENSION).as(where + ": rule_id " + ruleId + " ∈ pack v0").containsKey(ruleId);
        if (requireDimension) {
            assertThat(flag.path("dimension").asText())
                    .as(where + " dimension of " + ruleId).isEqualTo(REGISTRY_DIMENSION.get(ruleId));
        }
        String severity = flag.path("severity").asText("");
        assertThat(SEVERITIES).as(where + " severity of " + ruleId).contains(severity);
        assertThat(severity).as(where + " severity of " + ruleId + " matches pack v0")
                .isEqualTo(REGISTRY_SEVERITY.get(ruleId));
    }

    private List<String> idsOf(JsonNode flagsNode) {
        List<String> ids = new ArrayList<>();
        for (JsonNode n : flagsNode) {
            ids.add(n.path("rule_id").asText());
        }
        return ids;
    }

    private List<Path> fixtureFiles() throws IOException {
        Path dir = fixturesDir();
        assertThat(dir).as("tests/concurrence must exist at repo root").exists();
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().matches("issuer-.*\\.json"))
                    .sorted().toList();
        }
    }

    private boolean isIsoDate(String s) {
        if (s == null) {
            return false;
        }
        try {
            LocalDate.parse(s);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }
}
