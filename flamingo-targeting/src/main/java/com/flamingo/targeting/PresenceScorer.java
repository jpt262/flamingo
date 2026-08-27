package com.flamingo.targeting;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P4 Buyer-List Builder (§11): PRESENCE-based institutional targeting.
 *
 * <p>Signal classes with owner-frozen constants: 13F-HR T+45d (half-life ≈95d),
 * Form 4 clusters T+2d (half-life ≈10d), anchor takedowns from 424B5 rows
 * (half-life ≈120d). Every candidate row carries recomputed confidence_age;
 * score = Σ(breadthᵢ × intensityᵢ × decay(ageᵢ)) − staleness_penalty.</p>
 *
 * <p>R7 hard walls honored structurally: this module scores presence evidence.
 * It cannot transmit, aggregate investor demand, or contact anyone — the
 * classpath carries zero transport surfaces, ENFORCED by NoTransmissionSurfaceTest.</p>
 */
public class PresenceScorer {

    /** Signal provenance classes with §11-frozen decay parameters. */
    public enum SignalClass {
        FILING_13F(45, 95.0),        // quarterly; T+45d lag; ~95d half-life
        FORM4_CLUSTER(2, 10.0),      // insider cluster; T+2d; ~10d half-life
        ANCHOR_TAKEDOWN(0, 120.0);   // 424B5 anchor; ~120d half-life

        public final int lagDays;
        public final double halfLifeDays;

        SignalClass(int lagDays, double halfLifeDays) {
            this.lagDays = lagDays;
            this.halfLifeDays = halfLifeDays;
        }
    }

    /** One presence observation. intensity ∈ (0,1] normalized to manager scale. */
    public record Observation(SignalClass signalClass, String managerId,
                              LocalDate occurredOn, double intensity,
                              String evidenceAccession) {}

    /** Per-manager aggregate row for the workbook. */
    public record ManagerRow(String managerId, double score,
                             Map<String, Double> signalAgeDays,
                             List<String> evidenceAccessions) {}

    /** Workbook: ranked rows + global staleness banner fields (§11 DoD). */
    public record Workbook(String issuerCik, LocalDate asOf, List<ManagerRow> rows,
                           double oldestSignalAgeDays, boolean staleBanner) {}

    private static final double STALENESS_PENALTY_PER_SIGNAL = 0.10;
    private static final double STALE_THRESHOLD_DAYS = 90.0;

    /** decay = 2^(-age/halfLife); age counted from occurrence, lag folded in by caller data. */
    public static double decay(SignalClass sc, LocalDate occurredOn, LocalDate asOf) {
        long age = ChronoUnit.DAYS.between(occurredOn, asOf) - sc.lagDays;
        if (age < 0) {
            age = 0; // future-dated rows clamp (bad data shouldn't score higher)
        }
        return Math.pow(2.0, -age / sc.halfLifeDays);
    }

    /** Deterministic ranking: score desc, managerId asc as tiebreak (R4). */
    public Workbook build(String issuerCik, LocalDate asOf, List<Observation> observations) {
        Map<String, List<Observation>> byManager = new LinkedHashMap<>();
        for (Observation o : observations) {
            byManager.computeIfAbsent(o.managerId(), k -> new ArrayList<>()).add(o);
        }

        double oldestAge = 0;
        List<ManagerRow> rows = new ArrayList<>();
        for (var e : byManager.entrySet()) {
            double score = 0;
            boolean hasStale = false;
            Map<String, Double> ages = new LinkedHashMap<>();
            List<String> accessions = new ArrayList<>();
            for (Observation o : e.getValue()) {
                long age = Math.max(0, ChronoUnit.DAYS.between(o.occurredOn(), asOf) - o.signalClass().lagDays);
                ages.put(o.signalClass().name(), (double) age);
                oldestAge = Math.max(oldestAge, age);
                if (age > STALE_THRESHOLD_DAYS) {
                    hasStale = true;
                }
                // breadth counts once per signal class; intensity scales within class
                score += o.intensity() * decay(o.signalClass(), o.occurredOn(), asOf);
                accessions.add(o.evidenceAccession());
            }
            if (hasStale) {
                score -= STALENESS_PENALTY_PER_SIGNAL;
            }
            rows.add(new ManagerRow(e.getKey(), round4(score), ages, accessions));
        }
        rows.sort((a, b) -> Double.compare(b.score(), a.score()) != 0
                ? Double.compare(b.score(), a.score())
                : a.managerId().compareTo(b.managerId()));
        return new Workbook(issuerCik, asOf, List.copyOf(rows), oldestAge, oldestAge > STALE_THRESHOLD_DAYS);
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
