package com.flamingo.trunk.capstruct;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** T-09: layered reconstruction + DISCREPANCY guard (spec §7). */
class CapitalStructureReconstructorTest {

    private final CapitalStructureReconstructor r = new CapitalStructureReconstructor();

    private static CapitalStructureReconstructor.Observation obs(
            CapitalStructureReconstructor.Layer layer, String form, String date, String val) {
        return new CapitalStructureReconstructor.Observation(layer, form,
                LocalDate.parse(date), new BigDecimal(val), "citation:" + form);
    }

    @Test
    void saneStack_noDiscrepancy() {
        var state = new CapitalStructureReconstructor.IssuerCapitalState(1L, List.of(
                obs(CapitalStructureReconstructor.Layer.COVER_PAGE, "10-Q", "2026-08-01", "250000000"),
                obs(CapitalStructureReconstructor.Layer.BALANCE_SHEET, "10-K", "2025-12-31", "240000000"),
                obs(CapitalStructureReconstructor.Layer.FILING_DELTA, "S-8", "2026-01-15", "8000000"),
                obs(CapitalStructureReconstructor.Layer.FILING_DELTA, "424B5", "2026-03-01", "2000000")));
        var result = r.reconstruct(state);
        // computed = 240M (latest absolute) + 8M + 2M = 250M = stated → 0 divergence
        assertThat(result.computedValue().toPlainString()).isEqualTo("250000000");
        assertThat(result.statedValue().toPlainString()).isEqualTo("250000000");
        assertThat(result.discrepancies()).isEmpty();
        assertThat(DiscrepancyGuard.isBlocked(result)).isFalse();
    }

    @Test
    void divergenceBeyondHalfPercent_tripsDiscrepancyBanner() {
        var state = new CapitalStructureReconstructor.IssuerCapitalState(2L, List.of(
                obs(CapitalStructureReconstructor.Layer.BALANCE_SHEET, "10-K", "2025-12-31", "100000000"),
                obs(CapitalStructureReconstructor.Layer.COVER_PAGE, "10-Q", "2026-08-01", "102000000"),
                obs(CapitalStructureReconstructor.Layer.FILING_DELTA, "424B5", "2026-09-01", "8000000"))); // computed 110M vs stated 102M
        var result = r.reconstruct(state);
        assertThat(result.discrepancies()).hasSize(1);
        var d = result.discrepancies().get(0);
        assertThat(d.severity()).isEqualTo("blocking");
        assertThat(d.banner()).isEqualTo("DISCREPANCY");
        assertThat(DiscrepancyGuard.isBlocked(result)).isTrue();
    }

    @Test
    void divergenceExactlyAtHalfPercent_doesNotTrip() {
        var state = new CapitalStructureReconstructor.IssuerCapitalState(3L, List.of(
                obs(CapitalStructureReconstructor.Layer.COVER_PAGE, "10-Q", "2026-08-01", "1000000000"),
                obs(CapitalStructureReconstructor.Layer.FILING_DELTA, "424B5", "2026-09-01", "5000000"))); // exactly 0.5%
        var result = r.reconstruct(state);
        assertThat(result.discrepancies()).isEmpty();
    }

    @Test
    void stateVersionIsDeterministicAcrossOrderings() {
        var a = new CapitalStructureReconstructor.IssuerCapitalState(1L, List.of(
                obs(CapitalStructureReconstructor.Layer.COVER_PAGE, "10-Q", "2026-08-01", "250000000"),
                obs(CapitalStructureReconstructor.Layer.BALANCE_SHEET, "10-K", "2025-12-31", "240000000")));
        var b = new CapitalStructureReconstructor.IssuerCapitalState(9L, List.of(
                obs(CapitalStructureReconstructor.Layer.BALANCE_SHEET, "10-K", "2025-12-31", "240000000"),
                obs(CapitalStructureReconstructor.Layer.COVER_PAGE, "10-Q", "2026-08-01", "250000000")));
        assertThat(r.reconstruct(a).stateVersion())
                .isEqualTo(r.reconstruct(b).stateVersion());
    }

    @Test
    void noAbsoluteObservation_refusesLoudly() {
        var state = new CapitalStructureReconstructor.IssuerCapitalState(4L, List.of(
                obs(CapitalStructureReconstructor.Layer.FILING_DELTA, "S-8", "2026-01-15", "8000000")));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> r.reconstruct(state))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("refusing to guess");
    }
}
