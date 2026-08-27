package com.flamingo.trunk.instruments;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flamingo.trunk.concurrence.ConcurrenceCli;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** T-10: exhibit-snippet extraction → needs_confirmation queue (T-10 DoD). */
@Tag("db")
@EnabledIfEnvironmentVariable(named = "FLAMINGO_DB_TESTS", matches = "1")
class InstrumentsAutoExtractorTest {

        static JdbcTemplate jdbc;
    static InstrumentsAutoExtractor svc;
    static long companyId;
    static long evidenceId;

    static final String CONVERT_1 = "On March 3, 2026, the Company issued a convertible senior note "
            + "with an aggregate principal amount of $4,500,000 and a conversion price of $1.85 per share.";
    static final String CONVERT_2 = "The Subordinated Convertible Note carries a principal amount of "
            + "$1,200,000, convertible at $0.42 per share, maturing 2027.";
    static final String WARRANT_1 = "Pursuant to the agreement, the Investor received warrants to purchase "
            + "2,500,000 shares at an exercise price of $0.75, expiring March 3, 2031.";
    static final String WARRANT_2 = "The Company issued a warrant to purchase 600,000 shares with an "
            + "exercise price of $2.10 per share.";
    static final String ADVERSARIAL = "The Company and the Investor discussed potential future financing "
            + "arrangements in general terms; no securities were issued.";

    @BeforeAll
    static void up() throws Exception {
        var it = com.flamingo.trunk.ItDatabases.prepare("flamingo_instr_it");
        Flyway.configure().dataSource(it.jdbcUrl(), it.user(), it.password())
                .locations("classpath:db/migration").load().migrate();
        var ds = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                it.jdbcUrl(), it.user(), it.password());
        jdbc = new JdbcTemplate(ds);
        svc = new InstrumentsAutoExtractor(jdbc);
        jdbc.update("INSERT INTO companies (cik, entity_name) VALUES ('0009003001','Instr Issuer')");
        companyId = jdbc.queryForObject("SELECT id FROM companies WHERE cik='0009003001'", Long.class);
        jdbc.update("INSERT INTO evidence_refs (source_kind, locator_uri, trust_tier) "
                + "VALUES ('edgar_filing','test://exhibit','T2')");
        evidenceId = jdbc.queryForObject(
                "SELECT id FROM evidence_refs WHERE locator_uri='test://exhibit'", Long.class);
    }

    @Test
    void twoConvertsAndTwoWarrantsQueuedWithCitations_adversarialYieldsZero() {
        int landed = 0;
        for (String snippet : List.of(CONVERT_1, CONVERT_2, WARRANT_1, WARRANT_2)) {
            var out = svc.extractAndQueue(companyId, evidenceId, snippet);
            assertThat(out).as(snippet).hasSize(1);
            assertThat(out.get(0).citation()).contains("exhibit-snippet");
            landed += out.size();
        }
        assertThat(landed).isEqualTo(4);

        var adversarial = svc.extractAndQueue(companyId, evidenceId, ADVERSARIAL);
        assertThat(adversarial).isEmpty(); // never fabricate

        var stats = svc.queueStats(companyId);
        assertThat(stats.get("convertible_note")).isEqualTo(2);
        assertThat(stats.get("warrant")).isEqualTo(2);

        Integer allNeedsConfirmation = jdbc.queryForObject(
                "SELECT count(*) FROM instruments WHERE company_id=? AND extraction_status='needs_confirmation'",
                Integer.class, companyId);
        assertThat(allNeedsConfirmation).isEqualTo(4);
    }
}
