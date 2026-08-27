package com.flamingo.trunk.concurrence;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** T-20-v1: concurrence math incl. the seeded-disagreement fixture shape. */
class FlagSetComparisonTest {

    @Test
    void identicalSets_fullConcurrence() {
        var r = FlagSetComparison.compare(Set.of("DISC-001"), Set.of("DISC-001"));
        assertThat(r.concurrencePct()).isEqualTo(1.0);
    }

    @Test
    void emptyVsEmpty_fullConcurrence() {
        assertThat(FlagSetComparison.compare(Set.of(), Set.of()).concurrencePct()).isEqualTo(1.0);
    }

    @Test
    void delinquentFixtureShape_analystMissedFin002() {
        var r = FlagSetComparison.compare(
                Set.of("DISC-001", "FIN-002"),   // engine oracle: both
                Set.of("DISC-001"));              // analyst missed FIN-002
        assertThat(r.matchedCount()).isEqualTo(1);
        assertThat(r.engineOnly()).containsExactly("FIN-002");
        assertThat(r.analystOnly()).isEmpty();
        assertThat(r.concurrencePct()).isEqualTo(0.5);
    }

    @Test
    void analystSeesExtra_engineMissed() {
        var r = FlagSetComparison.compare(Set.of("FIN-001"), Set.of("FIN-001", "DISC-001"));
        assertThat(r.engineOnly()).isEmpty();
        assertThat(r.analystOnly()).containsExactly("DISC-001");
        assertThat(r.concurrencePct()).isEqualTo(1.0); // all engine flags matched
    }
}
