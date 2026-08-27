package com.flamingo.targeting;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** P4 scoring: frozen decay constants, determinism, staleness banner. */
class PresenceScorerTest {

    private static final LocalDate AS_OF = LocalDate.parse("2026-08-27");

    @Test
    void decayConstants_matchFrozenSpec() {
        // 13F: 95d half-life — at 95d age, decay = 0.5
        assertThat(PresenceScorer.decay(PresenceScorer.SignalClass.FILING_13F,
                AS_OF.minusDays(140), AS_OF)) // 140-45 lag = 95d effective age
                .isCloseTo(0.5, org.assertj.core.data.Offset.offset(0.001));
        // Form4: 10d half-life — at 20d effective age, decay = 0.25
        assertThat(PresenceScorer.decay(PresenceScorer.SignalClass.FORM4_CLUSTER,
                AS_OF.minusDays(22), AS_OF)) // 22-2 = 20d
                .isCloseTo(0.25, org.assertj.core.data.Offset.offset(0.001));
        // Anchor: 120d half-life — at 240d, decay = 0.25
        assertThat(PresenceScorer.decay(PresenceScorer.SignalClass.ANCHOR_TAKEDOWN,
                AS_OF.minusDays(240), AS_OF))
                .isCloseTo(0.25, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void fresh13f_beats_staleAnchor_despiteEqualIntensity() {
        var w = new PresenceScorer().build("0009000001", AS_OF, List.of(
                new PresenceScorer.Observation(PresenceScorer.SignalClass.FILING_13F,
                        "manager-A", AS_OF.minusDays(47), 0.5, "0000900001-26-000001"),
                new PresenceScorer.Observation(PresenceScorer.SignalClass.ANCHOR_TAKEDOWN,
                        "manager-B", AS_OF.minusDays(365), 0.5, "0000900001-25-000099")));
        assertThat(w.rows().get(0).managerId()).isEqualTo("manager-A");
        assertThat(w.rows().get(0).score()).isGreaterThan(w.rows().get(1).score());
    }

    @Test
    void stalenessPenalty_applies_andBannerFires() {
        var w = new PresenceScorer().build("0009000001", AS_OF, List.of(
                new PresenceScorer.Observation(PresenceScorer.SignalClass.FILING_13F,
                        "manager-stale", AS_OF.minusDays(200), 1.0, "0000900001-25-000001")));
        // base 1.0×decay(155d eff)≈0.324 − 0.10 penalty
        assertThat(w.rows().get(0).score()).isLessThan(0.33);
        assertThat(w.staleBanner()).isTrue();
    }

    @Test
    void deterministic_tiebreakByManagerId() {
        var o1 = new PresenceScorer.Observation(PresenceScorer.SignalClass.FILING_13F,
                "zz-fund", AS_OF.minusDays(50), 0.9, "acc-1");
        var o2 = new PresenceScorer.Observation(PresenceScorer.SignalClass.FILING_13F,
                "aa-fund", AS_OF.minusDays(50), 0.9, "acc-2");
        var w1 = new PresenceScorer().build("X", AS_OF, List.of(o1, o2));
        var w2 = new PresenceScorer().build("X", AS_OF, List.of(o2, o1));
        assertThat(w1.rows().get(0).managerId()).isEqualTo("aa-fund");
        assertThat(w1).isEqualTo(w2); // input order irrelevant (R4)
    }
}
