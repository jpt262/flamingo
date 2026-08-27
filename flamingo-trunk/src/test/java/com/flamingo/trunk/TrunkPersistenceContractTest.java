package com.flamingo.trunk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flamingo.edgar.RawStore;
import com.flamingo.trunk.evidence.ManifestWriter;
import com.flamingo.trunk.ingest.EnsureFilingsService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §5 migration convergence + supersession-regime writer contract, verified
 * against REAL Postgres (infra/docker-compose.yml database). Gated behind
 * FLAMINGO_DB_TESTS=1 so default offline builds never require a daemon
 * (CI runs these in the dedicated job where the service container exists).
 */
@Tag("db")
@EnabledIfEnvironmentVariable(named = "FLAMINGO_DB_TESTS", matches = "1")
class TrunkPersistenceContractTest {

    /** Boot strap used only to obtain a configured Flyway+DataSource context. */
    @SpringBootApplication
    static class TestBoot {
        // configuration-only
    }

    static ConfigurableApplicationContext ctx;
    static JdbcTemplate jdbc;

    @BeforeAll
    static void convergeFreshSchema() throws Exception {
        String host = env("FLYWAY_DB_HOST", "localhost");
        String port = env("FLYWAY_DB_PORT", "5434");
        // disposable per-run pad: drop/recreate so Flyway checksum edits never wedge us
        try (var c = java.sql.DriverManager.getConnection(
                "jdbc:postgresql://" + host + ":" + port + "/postgres",
                env("FLYWAY_DB_USER", "flamingo"), env("FLYWAY_DB_PASSWORD", "flamingo"));
             var st = c.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS flamingo_test WITH (FORCE)");
            st.execute("CREATE DATABASE flamingo_test");
        }
        ctx = new SpringApplication(TestBoot.class).run(
                "--spring.main.web-application-type=none",
                "--spring.flyway.locations=classpath:db/migration",
                "--spring.datasource.url=jdbc:postgresql://" + host + ":" + port + "/flamingo_test",
                "--spring.datasource.username=" + env("FLYWAY_DB_USER", "flamingo"),
                "--spring.datasource.password=" + env("FLYWAY_DB_PASSWORD", "flamingo"));
        jdbc = ctx.getBean(JdbcTemplate.class);
        // clean slate per run: identity tables truncated in FK-safe order (test-only;
        // production code paths are append-only forever)
        jdbc.execute("TRUNCATE manifests, gap_flags, evaluations, instruments, securities," +
                " facts, evidence_refs, archives, packet_runs, filings, companies RESTART IDENTITY CASCADE");
    }

    private static String env(String k, String dflt) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? dflt : v;
    }

    @Test
    void allSevenMigrationsConvergeOnFreshDatabase() {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables " +
                        "WHERE table_schema='public' AND table_name IN " +
                        "('companies','filings','evidence_refs','facts','securities'," +
                        "'instruments','evaluations','gap_flags','packet_runs','archives','manifests')",
                Integer.class);
        assertThat(count).isEqualTo(11);
    }

    @Test
    void ensureFilingsIsAppendOnlyAndIdempotent() throws Exception {
        EnsureFilingsService svc = new EnsureFilingsService(null, jdbc); // live client unused here
        long companyId = svc.ensureCompany("0000000001", "Synthetic Issuer Inc");

        String docJson = """
                {"filings":{"recent":{
                  "accessionNumber":["0000000001-26-000001","0000000001-26-000002"],
                  "form":["10-K","10-Q"],
                  "filingDate":["2026-03-15","2026-08-01"],
                  "reportDate":["2025-12-31","2026-06-30"]
                }}}
                """;
        JsonNode doc = new ObjectMapper().readTree(docJson);
        byte[] snapshotBytes = docJson.getBytes();
        RawStore.Stored snap = new RawStore.Stored(
                "test-source/snapshothash/20260827T000000Z.bin",
                RawStore.sha256Hex(snapshotBytes), snapshotBytes);

        Map<String, Object> first = svc.ensureFilingsFrom(companyId, doc, snap);
        assertThat(first.get("seen")).isEqualTo(2);
        assertThat(first.get("inserted")).isEqualTo(2);
        assertThat(first.get("raw_object_key")).isEqualTo(snap.objectKey());

        // provenance binding: rows point at the deriving snapshot's true bytes (R1/R2)
        Integer bound = jdbc.queryForObject(
                "SELECT count(*) FROM filings WHERE company_id=? AND raw_object_key=?"
                        + " AND raw_sha256=?",
                Integer.class, companyId, snap.objectKey(), snap.sha256());
        assertThat(bound).isEqualTo(2);

        // second identical run: conflict ⇒ DO NOTHING, zero writes (ruling Q1)
        Map<String, Object> second = svc.ensureFilingsFrom(companyId, doc, snap);
        assertThat(second.get("inserted")).isEqualTo(0);

        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM filings WHERE company_id=?", Integer.class, companyId);
        assertThat(rows).isEqualTo(2);
    }

    @Test
    void manifestChainAppendsVerifyAndTamperFailsLoudly() {
        ManifestWriter w = new ManifestWriter(jdbc);
        long s1 = w.append("artifacts/a1.pdf", "aa".repeat(32), List.of(1L, 2L), "build-test", "v0");
        long s2 = w.append("artifacts/a2.pdf", "bb".repeat(32), List.of(2L), "build-test", "v0");
        long s3 = w.append("artifacts/a3.pdf", "cc".repeat(32), List.of(), "build-test", "v0");
        assertThat(w.verifyChain()).as("fresh chain must be sound").isEmpty();

        // Tamper one artifact hash byte — verifier MUST catch it (§7/T-17 semantics)
        jdbc.update("UPDATE manifests SET artifact_sha256=? WHERE seq=?", "ff".repeat(32), s2);
        assertThat(w.verifyChain()).containsExactly(s2, s3); // tampered link and every successor
    }
}
