package com.flamingo.trunk.facts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flamingo.trunk.tags.TagDictionary;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T-07-lite DB contract: canonical extraction + evidence binding + R8
 * supersession closure + idempotency — per-run isolated database (fresh
 * V1–V8 schema), self-contained tests (no cross-test ordering assumptions).
 */
@Tag("db")
@EnabledIfEnvironmentVariable(named = "FLAMINGO_DB_TESTS", matches = "1")
class FactExtractionServiceTest {

    static JdbcTemplate jdbc;
    static FactExtractionService svc;
    static long companyId;
    static long evidenceId;
    static final ObjectMapper M = new ObjectMapper();

    @BeforeAll
    static void up() throws Exception {
        var it = com.flamingo.trunk.ItDatabases.prepare("flamingo_facts_it");
        Flyway.configure().dataSource(it.jdbcUrl(), it.user(), it.password())
                .locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                it.jdbcUrl(), it.user(), it.password()));
        svc = new FactExtractionService(jdbc, TagDictionary.loadDefault());

        jdbc.update("INSERT INTO companies (cik, entity_name) VALUES ('0009001001','IT Issuer')");
        companyId = jdbc.queryForObject("SELECT id FROM companies WHERE cik='0009001001'", Long.class);
        jdbc.update("INSERT INTO evidence_refs (source_kind, locator_uri, trust_tier) "
                + "VALUES ('edgar_filing','test://snapshot','T1')");
        evidenceId = jdbc.queryForObject(
                "SELECT id FROM evidence_refs WHERE locator_uri='test://snapshot'", Long.class);
    }

    private static void seedFiling(String accn, String filed) {
        jdbc.update("""
                INSERT INTO filings (company_id, accession, form_type, filed_at, raw_object_key, raw_sha256)
                VALUES (?, ?, '10-K', ?::timestamptz, 'k', 'sha')
                ON CONFLICT (accession) DO NOTHING
                """, companyId, accn, filed);
    }

    private static JsonNode facts(String accnA, String filedA, String accnB, String filedB) {
        try {
            return M.readTree("""
                {"facts":{"us-gaap":{
                  "CommonStockSharesOutstanding":{"units":{"shares":[
                    {"end":"2025-12-31","val":900000000,"accn":"%s","fy":2025,"fp":"FY","form":"10-K","filed":"%s"},
                    {"end":"2026-06-30","val":1150000000,"accn":"%s","fy":2026,"fp":"Q2","form":"10-K","filed":"%s"}
                  ]}},
                  "CommonStockSharesAuthorized":{"units":{"shares":[
                    {"end":"2025-12-31","val":800000000,"accn":"%s","fy":2025,"fp":"FY","form":"10-K","filed":"%s"}
                  ]}}
                }}}
                """.formatted(accnA, filedA, accnB, filedB, accnA, filedA));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void extractsCanonicalRows_bindsEvidence_supersedesOlderHistory() {
        seedFiling("0000900100-25-000001", "2025-03-15");
        seedFiling("0000900100-26-000001", "2026-03-15");

        var res = svc.extractFrom(
                facts("0000900100-25-000001", "2025-03-15",
                        "0000900100-26-000001", "2026-03-15"),
                companyId, evidenceId, null);

        assertThat((int) res.get("extracted")).isEqualTo(3);
        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM facts WHERE company_id=? AND xbrl_ref IN "
                        + "('0000900100-25-000001','0000900100-26-000001')",
                Integer.class, companyId);
        assertThat(rows).isEqualTo(3);

        Integer outstanding = jdbc.queryForObject("""
                SELECT count(*) FROM facts WHERE company_id=? AND tag='SharesOutstanding'
                        AND evidence_id=? AND xbrl_ref IN
                        ('0000900100-25-000001','0000900100-26-000001')
                """, Integer.class, companyId, evidenceId);
        assertThat(outstanding).isEqualTo(2);

        Integer open = jdbc.queryForObject("""
                SELECT count(*) FROM facts WHERE company_id=? AND tag='SharesOutstanding'
                        AND valid_to IS NULL AND xbrl_ref IN
                        ('0000900100-25-000001','0000900100-26-000001')
                """, Integer.class, companyId);
        assertThat(open).isEqualTo(1);
        Integer superseded = jdbc.queryForObject("""
                SELECT count(*) FROM facts WHERE company_id=? AND tag='SharesOutstanding'
                        AND superseded_by IS NOT NULL AND valid_to IS NOT NULL AND xbrl_ref IN
                        ('0000900100-25-000001','0000900100-26-000001')
                """, Integer.class, companyId);
        assertThat(superseded).isEqualTo(1);
    }

    @Test
    void idempotentReRun_zeroDuplicateRows() {
        seedFiling("0000900150-27-000001", "2027-03-15");
        seedFiling("0000900150-28-000001", "2028-03-15");
        var doc = facts("0000900150-27-000001", "2027-03-15",
                "0000900150-28-000001", "2028-03-15");

        var first = svc.extractFrom(doc, companyId, evidenceId, null);
        assertThat((int) first.get("extracted")).isEqualTo(3);

        var second = svc.extractFrom(doc, companyId, evidenceId, null);
        assertThat((int) second.get("extracted")).isEqualTo(0);

        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM facts WHERE company_id=? AND tag='SharesOutstanding'",
                Integer.class, companyId);
        assertThat(rows).isEqualTo(2); // still exactly two rows for this concept
    }

    @Test
    void unboundAccession_rowsSkippedNeverOrphaned() {
        seedFiling("0000900100-29-000001", "2029-03-15"); // only ONE of two exists
        var doc = facts("0000900100-29-000001", "2029-03-15",
                "0000900100-99-999999", "2099-03-15");

        var res = svc.extractFrom(doc, companyId, evidenceId, null);
        assertThat((int) res.get("skipped_unbound")).isGreaterThanOrEqualTo(1);
        // no fact row references the missing accession
        Integer orphans = jdbc.queryForObject("""
                SELECT count(*) FROM facts f WHERE company_id=? AND NOT EXISTS
                  (SELECT 1 FROM filings g WHERE g.accession = f.xbrl_ref)
                """, Integer.class, companyId);
        assertThat(orphans).isEqualTo(0);
    }
}
