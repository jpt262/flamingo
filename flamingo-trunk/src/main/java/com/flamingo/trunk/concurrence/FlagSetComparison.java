package com.flamingo.trunk.concurrence;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * T-20-v1 blind-parallel concurrence math: compares ENGINE flag set vs ANALYST
 * flag set (blind = neither side sees the other before emitting). Pure module —
 * the T-06 fixtures are plain data to it.
 *
 * <p>concurrencePct = matched / engineTotal (1.0 when both sides empty).</p>
 */
public final class FlagSetComparison {

    public record Result(int matchedCount, List<String> engineOnly,
                         List<String> analystOnly, double concurrencePct) {}

    private FlagSetComparison() {}

    public static Result compare(Set<String> engineFlags, Set<String> analystFlags) {
        Set<String> engine = engineFlags == null ? Set.of() : engineFlags;
        Set<String> analyst = analystFlags == null ? Set.of() : analystFlags;

        Set<String> matched = new LinkedHashSet<>(engine);
        matched.retainAll(analyst);

        Set<String> engineOnly = new LinkedHashSet<>(engine);
        engineOnly.removeAll(analyst);
        Set<String> analystOnly = new LinkedHashSet<>(analyst);
        analystOnly.removeAll(engine);

        double pct = engine.isEmpty()
                ? (analyst.isEmpty() ? 1.0 : 0.0)
                : (double) matched.size() / engine.size();
        return new Result(matched.size(), List.copyOf(engineOnly),
                List.copyOf(analystOnly), pct);
    }
}
