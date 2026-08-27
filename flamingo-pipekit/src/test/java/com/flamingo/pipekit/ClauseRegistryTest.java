package com.flamingo.pipekit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** P6b: registry + assembly; production_ready false while clauses pending review. */
@Tag("db")
@EnabledIfEnvironmentVariable(named = "FLAMINGO_DB_TESTS", matches = "1")
class ClauseRegistryTest {

    private final ObjectMapper m = new ObjectMapper();

    @Test
    void registerAssembleAndReviewGate() throws Exception {
        var it = com.flamingo.trunk.ItDatabases.prepare("flamingo_pipe_it");
        org.flywaydb.core.Flyway.configure().dataSource(it.jdbcUrl(), it.user(), it.password())
                .locations("classpath:db/migration").load().migrate();
        var jdbc = new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                it.jdbcUrl(), it.user(), it.password()));
        var svc = new ClauseRegistry(jdbc);

        svc.register("liq-pref-1x", "economic", "1x Non-Participating Preference",
                "The Series A Preferred shall rank senior to common…", "pending");
        svc.register("board-seat", "governance", "Investor Director", "Investor may designate…", "approved");

        var full = svc.list(null, false);
        assertThat(full).hasSize(2);

        // assembly with pending clause → production_ready false
        var out = svc.assemble("Seed PIPE", List.of("liq-pref-1x", "board-seat"));
        assertThat(out.get("production_ready")).isEqualTo(false);

        // approved-only filter hides the pending one
        assertThat(svc.list(null, true)).extracting(ClauseRegistry.Clause::clauseKey)
                .containsExactly("board-seat");

        // unknown clause fails loudly
        assertThatThrownBy(() -> svc.assemble("X", List.of("nope")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown clause");
    }
}
