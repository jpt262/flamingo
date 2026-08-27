package com.flamingo.trunk.restoration;

import com.flamingo.trunk.concurrence.ConcurrenceCli;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** T-14: restoration state machine vs real PG (V8 additive migration). */
@Tag("db")
@EnabledIfEnvironmentVariable(named = "FLAMINGO_DB_TESTS", matches = "1")
class RestorationCaseServiceTest {

        static JdbcTemplate jdbc;
    static RestorationCaseService svc;

    @BeforeAll
    static void up() throws Exception {
        var it = com.flamingo.trunk.ItDatabases.prepare("flamingo_rest_it");
        Flyway.configure().dataSource(it.jdbcUrl(), it.user(), it.password())
                .locations("classpath:db/migration").load().migrate();
        var ds = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                it.jdbcUrl(), it.user(), it.password());
        jdbc = new JdbcTemplate(ds);
        svc = new RestorationCaseService(jdbc);
        jdbc.update("INSERT INTO companies (cik, entity_name) VALUES ('0009002001','Rest Issuer')");
    }

    @Test
    void fullHappyPath_withDriftBackAndExports() {
        long companyId = jdbc.queryForObject(
                "SELECT id FROM companies WHERE cik='0009002001'", Long.class);
        long caseId = svc.createCase(companyId, "tier:standard");

        assertThat(svc.currentStatus(caseId)).isNull();

        svc.recordTransition(caseId, "Engaged", "Diagnosed", "analyst-1", "gap report priced");
        svc.recordTransition(caseId, "Diagnosed", "Remediation", "analyst-1", null);
        svc.recordTransition(caseId, "Remediation", "CatchUp", "analyst-2", null);
        svc.recordTransition(caseId, "CatchUp", "CurrentInfo", "analyst-2", null);
        svc.recordTransition(caseId, "CurrentInfo", "ReadyFor211", "analyst-2", null);
        svc.recordTransition(caseId, "ReadyFor211", "Quoted", "principal-1", "FINRA 6432 cleared");
        svc.recordTransition(caseId, "Quoted", "Monitored", "principal-1", null);
        assertThat(svc.currentStatus(caseId)).isEqualTo("Monitored");

        // drift-back: staleness auto-reopen edge
        svc.recordTransition(caseId, "Monitored", "CatchUp", "system-staleness", "drift-back");
        assertThat(svc.currentStatus(caseId)).isEqualTo("CatchUp");

        // events append-only
        Integer events = jdbc.queryForObject(
                "SELECT count(*) FROM restoration_case_events WHERE case_id=?",
                Integer.class, caseId);
        assertThat(events).isEqualTo(8);
    }

    @Test
    void illegalTransition_failsLoudly() {
        long companyId = jdbc.queryForObject(
                "SELECT id FROM companies WHERE cik='0009002001'", Long.class);
        jdbc.update("INSERT INTO companies (cik, entity_name) VALUES ('0009002002','Rest Issuer 2') "
                + "ON CONFLICT (cik) DO NOTHING");
        long c2 = jdbc.queryForObject(
                "SELECT id FROM companies WHERE cik='0009002002'", Long.class);
        long caseId = svc.createCase(c2, "");
        svc.recordTransition(caseId, "Engaged", "Diagnosed", "a", null);

        assertThatThrownBy(() ->
                svc.recordTransition(caseId, "Diagnosed", "Quoted", "a", "skip the machine"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ILLEGAL");
        // state unchanged
        assertThat(svc.currentStatus(caseId)).isEqualTo("Diagnosed");
    }

    @Test
    void abandonedIsReachableFromEarlyStates_only() {
        long c3 = jdbc.queryForObject(
                "INSERT INTO companies (cik, entity_name) VALUES ('0009002003','R3') RETURNING id",
                Long.class);
        long caseId = svc.createCase(c3, "");
        svc.recordTransition(caseId, "Engaged", "Abandoned", "principal-1", "no viable path");
        assertThat(svc.currentStatus(caseId)).isEqualTo("Abandoned");
        // Abandoned is terminal: no edges
        assertThatThrownBy(() ->
                svc.recordTransition(caseId, "Abandoned", "Engaged", "a", "resurrect"))
                .isInstanceOf(IllegalStateException.class);
    }
}
