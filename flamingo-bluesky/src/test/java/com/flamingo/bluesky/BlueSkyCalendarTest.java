package com.flamingo.bluesky;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** P6a: deterministic calendar from qualification date; status rollup. */
class BlueSkyCalendarTest {

    @Test
    void generatesDueDates_fromQualification() {
        var q = LocalDate.parse("2026-09-01");
        var entries = BlueSkyCalendar.generate(q, List.of("NY", "CA"), Map.of());
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).stateCode()).isEqualTo("NY");
        assertThat(entries.get(0).dueDate()).isEqualTo(q.plusDays(30));
        assertThat(entries.get(1).dueDate()).isEqualTo(q.plusDays(15));
    }

    @Test
    void statusRollup_filedPlannedOverdue() {
        var past = LocalDate.parse("2026-01-01");
        var entries = BlueSkyCalendar.generate(past, List.of("NY", "TX"),
                Map.of("NY", LocalDate.parse("2026-01-10")));
        assertThat(entries.get(0).status()).isEqualTo("filed");    // NY filed
        assertThat(entries.get(1).status()).isEqualTo("overdue");  // TX due Jan 11, past
    }
}
