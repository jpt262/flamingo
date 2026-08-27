package com.flamingo.trunk.capstruct;

/**
 * P1 packet-generation gate (spec §7/§8): generation REFUSES issuers whose
 * capital-structure reconstruction carries a DISCREPANCY. Pure seam, no DB.
 */
public final class DiscrepancyGuard {

    private DiscrepancyGuard() {}

    public static boolean isBlocked(CapitalStructureReconstructor.CapitalStructureResult r) {
        return r != null && !r.discrepancies().isEmpty();
    }
}
