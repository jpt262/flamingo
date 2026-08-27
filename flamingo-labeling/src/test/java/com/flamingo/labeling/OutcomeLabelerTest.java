package com.flamingo.labeling;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** P7: vocabulary enforcement + label harvest scoreboard. */
@Tag("db")
@EnabledIfEnvironmentVariable(named = "FLAMINGO_DB_TESTS", matches = "1")
class OutcomeLabelerTest {

    static JdbcTemplate jdbc;
    static OutcomeLabeler svc;
    static long companyId;

    @BeforeAll
    static void up() throws Exception {
        var it = com.flamingo.trunk.ItDatabases.prepare("flamingo_label_it");
        org.flywaydb.core.Flyway.configure().dataSource(it.jdbcUrl(), it.user(), it.password())
                .locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                it.jdbcUrl(), it.user(), it.password()));
        svc = new OutcomeLabeler(jdbc);
        jdbc.update("INSERT INTO companies (cik, entity_name) VALUES ('0009004001','Label Co')");
        companyId = jdbc.queryForObject("SELECT id FROM companies WHERE cik='0009004001'", Long.class);
    }

    @Test
    void vocabularyEnforced_loudlyUnknown() {
        assertThatThrownBy(() -> svc.label(companyId, null, "mooned_hard",
                java.time.LocalDate.parse("2026-08-27"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ADR-0007");
    }

    @Test
    void labelAndDistribution() {
        svc.label(companyId, null, "delinquency_resolved",
                java.time.LocalDate.parse("2026-08-01"), "cured via restoration workspace");
        svc.label(companyId, null, "severe_dilution",
                java.time.LocalDate.parse("2026-08-15"), ">50% dilution in 6mo");
        var dist = svc.distribution();
        assertThat(dist.get("delinquency_resolved")).isEqualTo(1L);
        assertThat(dist.get("severe_dilution")).isEqualTo(1L);
        assertThat(svc.history(companyId)).hasSize(2);
    }
}
