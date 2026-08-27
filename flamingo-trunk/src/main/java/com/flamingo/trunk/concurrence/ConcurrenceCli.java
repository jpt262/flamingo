package com.flamingo.trunk.concurrence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Stream;

/**
 * T-20-v1 CLI: scans tests/concurrence/issuer-*.json and prints per-scenario +
 * aggregate concurrence (expected_flags as engine-oracle side vs
 * analyst_judgment.flags). stdout = JSON only; exit 0 always (report, not gate).
 */
public final class ConcurrenceCli {

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args.length > 0 ? args[0] : "tests/concurrence");
        ObjectMapper m = new ObjectMapper();
        com.fasterxml.jackson.databind.node.ArrayNode out = m.createArrayNode();
        int scenarios = 0;
        double sum = 0;

        try (Stream<Path> files = Files.list(dir)) {
            var sorted = files
                    .filter(p -> p.getFileName().toString().matches("issuer-.*\\.json"))
                    .sorted().toList();
            for (Path p : sorted) {
                JsonNode f = m.readTree(p.toFile());
                Set<String> engine = new LinkedHashSet<>();
                f.path("expected_flags").forEach(n -> engine.add(n.path("rule_id").asText()));
                Set<String> analyst = new LinkedHashSet<>();
                f.path("analyst_judgment").path("flags")
                        .forEach(n -> analyst.add(n.path("rule_id").asText()));
                FlagSetComparison.Result r = FlagSetComparison.compare(engine, analyst);
                scenarios++;
                sum += r.concurrencePct();
                var o = out.addObject();
                o.put("scenario", f.path("scenario").asText());
                o.put("engine", engine.size());
                o.put("analyst", analyst.size());
                o.put("matched", r.matchedCount());
                o.put("engine_only", String.join(",", r.engineOnly()));
                o.put("analyst_only", String.join(",", r.analystOnly()));
                o.put("concurrence_pct", Math.round(r.concurrencePct() * 1000) / 1000.0);
            }
        }
        var root = m.createObjectNode();
        root.put("scenarios", scenarios);
        root.put("aggregate_concurrence_pct",
                scenarios == 0 ? 1.0 : Math.round(sum / scenarios * 1000) / 1000.0);
        root.set("per_scenario", out);
        System.out.println(m.writerWithDefaultPrettyPrinter().writeValueAsString(root));
    }
}
