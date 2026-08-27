package com.flamingo.rights;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * P5 rights-offering arithmetic (§12): record-date entitlements,
 * oversubscription tally, standby-backstop allocation. PURE FUNCTIONS —
 * every caller surface carries PENDING-COUNSEL semantics (ADR-0012);
 * distribution is executed exclusively by the partner broker-dealer (R7).
 */
public final class RightsMath {

    public record Offering(int ratioShares, int ratioHeld, BigDecimal subscriptionPrice,
                           BigDecimal totalSharesOffered, BigDecimal standbyShares) {}

    public record HolderEntitlement(long holderId, BigDecimal heldShares,
                                    BigDecimal baseEntitlement) {}

    public record Allocation(long holderId, BigDecimal baseShares,
                             BigDecimal oversubscribedShares, BigDecimal standbyShares,
                             BigDecimal totalAllocated, BigDecimal cost) {}

    public record TalliedResult(List<Allocation> allocations,
                                BigDecimal unsubscribedBase,
                                BigDecimal standbyAllocated,
                                boolean counselConfirmationRequired) {}

    private RightsMath() {}

    /** Base entitlement = floor(held × ratioShares / ratioHeld). */
    public static BigDecimal entitlement(BigDecimal heldShares, Offering o) {
        return heldShares.multiply(BigDecimal.valueOf(o.ratioShares()))
                .divide(BigDecimal.valueOf(o.ratioHeld()), 0, RoundingMode.DOWN);
    }

    /**
     * Full tally: base entitlements first, then oversubscription from the
     * pool of unsubscribed base shares, remainder to standby backstop.
     * Deterministic: holders processed in given order, first-come for overs.
     */
    public static TalliedResult tally(Offering o, List<HolderEntitlement> holders,
                                      Map<Long, BigDecimal> oversubscription) {
        List<Allocation> out = new ArrayList<>();
        BigDecimal poolRemaining = o.totalSharesOffered();

        // pass 1: base entitlements
        Map<Long, BigDecimal> bases = new java.util.LinkedHashMap<>();
        for (HolderEntitlement h : holders) {
            BigDecimal base = entitlement(h.heldShares(), o).min(poolRemaining.max(BigDecimal.ZERO));
            bases.put(h.holderId(), base);
            poolRemaining = poolRemaining.subtract(base);
        }

        // pass 2: oversubscription from remaining pool (request clamped to pool)
        Map<Long, BigDecimal> overs = new java.util.LinkedHashMap<>();
        for (HolderEntitlement h : holders) {
            BigDecimal requested = oversubscription.getOrDefault(h.holderId(), BigDecimal.ZERO);
            BigDecimal granted = requested.min(poolRemaining.max(BigDecimal.ZERO));
            overs.put(h.holderId(), granted);
            poolRemaining = poolRemaining.subtract(granted);
        }

        // pass 3: remainder to standby backstop
        BigDecimal standbyUsed = BigDecimal.ZERO;
        if (o.standbyShares() != null && poolRemaining.signum() > 0) {
            standbyUsed = poolRemaining.min(o.standbyShares());
        }

        for (HolderEntitlement h : holders) {
            BigDecimal base = bases.get(h.holderId());
            BigDecimal over = overs.get(h.holderId());
            BigDecimal fromStandby = BigDecimal.ZERO;
            if (standbyUsed.signum() > 0 && o.standbyShares() != null
                    && oversubscription.getOrDefault(h.holderId(), BigDecimal.ZERO)
                       .compareTo(over) > 0) {
                // standby backstop covers pro-rata share of the shortfall
                BigDecimal shortfall = oversubscription.get(h.holderId()).subtract(over);
                BigDecimal standbyPoolLeft = o.standbyShares().subtract(standbyUsed);
                fromStandby = shortfall.min(standbyPoolLeft);
                standbyUsed = standbyUsed.add(fromStandby);
            }
            BigDecimal total = base.add(over).add(fromStandby);
            out.add(new Allocation(h.holderId(), base, over, fromStandby, total,
                    total.multiply(o.subscriptionPrice())));
        }
        // "Unsubscribed base" = shares rights holders did NOT take = what the
        // standby backstop party covers (minus any truly unsold remainder if
        // standby capacity is insufficient). Standby purchases are attributed
        // to the backstop party, not to holder rows.
        BigDecimal holderTaken = out.stream()
                .map(a -> a.baseShares().add(a.oversubscribedShares()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new TalliedResult(List.copyOf(out),
                o.totalSharesOffered().subtract(holderTaken),
                standbyUsed,
                true); // P5 surfaces always declare PENDING-COUNSEL until counsel confirms
    }
}
