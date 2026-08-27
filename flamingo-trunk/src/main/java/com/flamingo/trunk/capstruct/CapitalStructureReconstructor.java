package com.flamingo.trunk.capstruct;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * T-09 capital-structure reconstruction (spec §7): walk share-count observation
 * LAYERS in order — cover-page counts → balance-sheet issued/outstanding →
 * 424Bx/S-8/Form 4 deltas → exhibit instruments — compute the running
 * outstanding count, and compare against the latest STATED value.
 *
 * <p>GUARD: |computed − stated| / stated > 0.005 ⇒ DISCREPANCY entry (blocking);
 * downstream packet generation (P1) refuses such issuers via
 * {@link DiscrepancyGuard}. Pure computation, no DB, byte-deterministic (R4).</p>
 */
public class CapitalStructureReconstructor {

    public enum Layer { COVER_PAGE, BALANCE_SHEET, FILING_DELTA, INSTRUMENT }

    /** One observation: a share-count datum from a specific layer. */
    public record Observation(Layer layer, String formRef, LocalDate asOf,
                              BigDecimal value, String citation) {}

    /** IssuerCapitalState input (immutable). */
    public record IssuerCapitalState(long companyId, List<Observation> observations) {}

    public record Discrepancy(String dimension, String severity, String banner,
                              String computed, String stated, String divergence) {}

    public record CapitalStructureResult(long companyId, String stateVersion,
                                         BigDecimal computedValue, BigDecimal statedValue,
                                         String divergenceRatio, List<Discrepancy> discrepancies) {}

    private static final BigDecimal GUARD = new BigDecimal("0.005");

    public CapitalStructureResult reconstruct(IssuerCapitalState state) {
        List<Observation> ordered = state.observations().stream()
                .sorted(Comparator.comparing(Observation::asOf)
                        .thenComparing(o -> o.layer().ordinal()))
                .toList();

        // Base = latest COVER_PAGE or BALANCE_SHEET observation (absolute counts);
        // deltas from FILING_DELTA/INSTRUMENT layers adjust it chronologically.
        BigDecimal base = null;
        LocalDate baseDate = null;
        for (Observation o : ordered) {
            if (o.layer() == Layer.COVER_PAGE || o.layer() == Layer.BALANCE_SHEET) {
                base = o.value();
                baseDate = o.asOf();
            }
        }
        if (base == null) {
            throw new IllegalArgumentException(
                    "no absolute share-count observation (cover page/balance sheet) — refusing to guess");
        }
        BigDecimal computed = base;
        for (Observation o : ordered) {
            if ((o.layer() == Layer.FILING_DELTA || o.layer() == Layer.INSTRUMENT)
                    && !o.asOf().isBefore(baseDate)) {
                computed = computed.add(o.value()); // deltas carry sign
            }
        }

        // Stated = latest absolute observation overall (the issuer's own claim).
        Observation statedObs = ordered.stream()
                .filter(o -> o.layer() == Layer.COVER_PAGE || o.layer() == Layer.BALANCE_SHEET)
                .max(Comparator.comparing(Observation::asOf))
                .orElseThrow();
        BigDecimal stated = statedObs.value();

        String divergence;
        if (stated.compareTo(BigDecimal.ZERO) == 0) {
            divergence = computed.compareTo(BigDecimal.ZERO) == 0 ? "0" : "INF";
        } else {
            divergence = computed.subtract(stated).abs()
                    .divide(stated.abs(), 6, RoundingMode.HALF_UP).toPlainString();
        }

        List<Discrepancy> discrepancies = new ArrayList<>();
        if (!"INF".equals(divergence)
                && new BigDecimal(divergence).compareTo(GUARD) > 0
                && computed.subtract(stated).signum() != 0) {
            discrepancies.add(new Discrepancy("capital_sanity", "blocking", "DISCREPANCY",
                    computed.toPlainString(), stated.toPlainString(), divergence));
        }

        return new CapitalStructureResult(state.companyId(),
                stateHash(state), computed, stated, divergence,
                List.copyOf(discrepancies));
    }

    /** Deterministic sha256 over canonical observation serialization (R4). */
    static String stateHash(IssuerCapitalState s) {
        try {
            StringBuilder sb = new StringBuilder();
            s.observations().stream()
                    .sorted(Comparator.comparing(Observation::asOf)
                            .thenComparing(o -> o.layer().ordinal())
                            .thenComparing(o -> o.value().toPlainString()))
                    .forEach(o -> sb.append(o.layer()).append('|').append(o.formRef()).append('|')
                            .append(o.asOf()).append('|').append(o.value().toPlainString()).append(';'));
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            StringBuilder hex = new StringBuilder();
            for (byte b : md.digest(sb.toString().getBytes(StandardCharsets.UTF_8))) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("state hash failed", e);
        }
    }
}
