package com.flamingo.rights;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** P5 arithmetic: entitlement, oversubscription pool, standby backstop. */
class RightsMathTest {

    private final RightsMath.Offering offering = new RightsMath.Offering(
            1, 10, new BigDecimal("0.85"),          // 1-for-10 at $0.85
            new BigDecimal("100000"), new BigDecimal("20000"));

    @Test
    void baseEntitlement_floorDivision() {
        // 4,567 held × 1/10 = 456.7 → floor 456
        assertThat(RightsMath.entitlement(new BigDecimal("4567"), offering))
                .isEqualByComparingTo("456");
    }

    @Test
    void fullTally_baseOversAndStandby() {
        var holders = List.of(
                new RightsMath.HolderEntitlement(1L, new BigDecimal("500000"), null),
                new RightsMath.HolderEntitlement(2L, new BigDecimal("300000"), null),
                new RightsMath.HolderEntitlement(3L, new BigDecimal("100000"), null));
        // bases: 50000, 30000, 10000 = 90000; pool left 10000
        var overs = Map.of(1L, new BigDecimal("8000"),   // fully granted
                2L, new BigDecimal("5000"));              // granted 2000 (pool limit)
        var r = RightsMath.tally(offering, holders, overs);

        assertThat(r.allocations().get(0).baseShares()).isEqualByComparingTo("50000");
        assertThat(r.allocations().get(0).oversubscribedShares()).isEqualByComparingTo("8000");
        assertThat(r.allocations().get(1).oversubscribedShares()).isEqualByComparingTo("2000");
        assertThat(r.allocations().get(2).baseShares()).isEqualByComparingTo("10000");
        // pool exhausted → standby untouched
        assertThat(r.standbyAllocated()).isEqualByComparingTo("0");
        assertThat(r.counselConfirmationRequired()).isTrue(); // PENDING-COUNSEL always declared
    }

    @Test
    void standbyBackstop_coversShortfall_whenPoolInsufficient() {
        var offering2 = new RightsMath.Offering(1, 10, new BigDecimal("0.85"),
                new BigDecimal("60000"), new BigDecimal("100000")); // small offering, big standby
        var holders = List.of(
                new RightsMath.HolderEntitlement(1L, new BigDecimal("300000"), null),
                new RightsMath.HolderEntitlement(2L, new BigDecimal("300000"), null));
        // bases 30000 each = 60000 = fully covered by holders → standby untouched
        var r1 = RightsMath.tally(offering2, holders, Map.of());
        assertThat(r1.standbyAllocated()).isEqualByComparingTo("0");
        assertThat(r1.unsubscribedBase()).isEqualByComparingTo("0");

        // undersubscription: holder 2 absent, their 30000 base unsubscribed → standby covers
        var r2 = RightsMath.tally(offering2,
                List.of(new RightsMath.HolderEntitlement(1L, new BigDecimal("300000"), null)),
                Map.of());
        assertThat(r2.allocations().get(0).baseShares()).isEqualByComparingTo("30000");
        assertThat(r2.standbyAllocated()).isEqualByComparingTo("30000");
        // unsubscribedBase = what holders didn't take = what standby covered
        assertThat(r2.unsubscribedBase()).isEqualByComparingTo("30000");
    }

    @Test
    void cost_isTotalTimesPrice() {
        var r = RightsMath.tally(offering,
                List.of(new RightsMath.HolderEntitlement(9L, new BigDecimal("100000"), null)),
                Map.of());
        // base 10000 × 0.85
        assertThat(r.allocations().get(0).cost()).isEqualByComparingTo("8500.00");
    }
}
