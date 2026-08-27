package com.flamingo.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.flamingo.edgar.EdgarClient;
import com.flamingo.edgar.EdgarConfig;
import com.flamingo.trunk.evidence.ManifestWriter;
import com.flamingo.trunk.ingest.EnsureFilingsService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Flamingo bootstrap.
 *
 * <p>Modes (via {@code --task=} / LIVE_NET gating):</p>
 * <ul>
 *   <li>{@code serve} (DEFAULT) — operator cockpit: verify chain, boot web tier,
 *       STAY UP serving {@code /index.html} + APIs until stopped.</li>
 *   <li>{@code smoke} — LIVE_NET=1 single-CIK ingest demo, then exit.</li>
 *   <li>{@code golden} — LIVE_NET=1 T-05 month-window FTS corpus build, then exit.</li>
 * </ul>
 *
 * <p>Every network-touching mode requires SEC_USER_AGENT. The smoke/golden paths
 * are the ONLY code paths that touch live EDGAR.</p>
 */
@SpringBootApplication
public class FlamingoApp {

    public static void main(String[] args) {
        ConfigurableApplicationContext ctx = SpringApplication.run(FlamingoApp.class, args);

        Environment env = ctx.getEnvironment();
        JdbcTemplate jdbc = ctx.getBean(JdbcTemplate.class);

        ManifestWriter writer = new ManifestWriter(jdbc);
        List<Long> broken = writer.verifyChain();
        System.out.println("[flamingo] manifest chain verify: "
                + (broken.isEmpty() ? "SOUND" : "BROKEN seqs=" + broken));

        String task = env.getProperty("task", "serve");

        // ── serve mode: hand the process to Tomcat's non-daemon threads ──
        if ("serve".equalsIgnoreCase(task)) {
            System.out.println("[flamingo] LIVE_NET=0 posture — cockpit serving on http://localhost:"
                    + env.getProperty("server.port", "8080")
                    + "/  (stop with Ctrl+C)");
            return; // NO ctx.close(): threads keep the JVM alive
        }

        // ── one-shot task modes: run, close cleanly, exit ──
        try {
            boolean live = Boolean.parseBoolean(env.getProperty("livenet",
                    String.valueOf(Boolean.parseBoolean(
                            System.getenv().getOrDefault("LIVE_NET", "0")))));
            if (!live) {
                System.out.println("[flamingo] LIVE_NET=0 — task '" + task
                        + "' requires --livenet=true; nothing performed");
                return;
            }
            String ua = env.getProperty("sec-user-agent",
                    System.getenv().getOrDefault("SEC_USER_AGENT", ""));
            if (ua == null || ua.isBlank()) {
                System.err.println("[flamingo] task '" + task + "' requires SEC_USER_AGENT");
                System.exit(3);
            }

            EdgarConfig cfg = new EdgarConfig(ua,
                    Path.of(env.getProperty("flamingo.raw-store", ".data/rawstore")),
                    4.0, 4, java.time.Duration.ofMillis(500));
            try (EdgarClient client = new EdgarClient(cfg)) {
                if ("golden".equalsIgnoreCase(task)) {
                    try {
                        goldenTask(env, client);
                    } catch (IOException ioe) {
                        System.err.println("[flamingo] GOLDEN corpus build failed: " + ioe);
                        System.exit(4);
                    }
                } else {
                    smokeTask(env, jdbc, client);
                }
            }
        } finally {
            ctx.close();
        }
    }

    private static void goldenTask(Environment env, EdgarClient client) throws IOException {
        var job = new com.flamingo.trunk.golden.GoldenCorpusJob(client,
                Path.of(env.getProperty("golden.dir", "tests/golden")));
        int target = Integer.parseInt(env.getProperty("golden.target", "200"));
        java.time.YearMonth cursor = java.time.YearMonth.now().minusMonths(1);
        List<java.time.YearMonth> windows = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            windows.add(cursor.minusMonths(i));
        }
        Map<String, Object> res = job.run(target, windows,
                new com.flamingo.trunk.golden.GoldenCorpusJob.ProgressListener() {
                    public void windowDone(java.time.YearMonth ym, int d, int h) {
                        System.out.println("[flamingo] FTS " + ym + ": discovered=" + d);
                    }
                    public void docAcquired(String adsh, int have, int t) {
                        System.out.println("[flamingo] corpus " + have + "/" + t + " ← " + adsh);
                    }
                    public void docFailed(String adsh, String why) {
                        System.out.println("[flamingo] SKIP " + adsh + " : " + why);
                    }
                });
        System.out.println("[flamingo] GOLDEN RESULT: " + res);
    }

    private static void smokeTask(Environment env, JdbcTemplate jdbc, EdgarClient client) {
        String cik = env.getProperty("smoke.cik", "0000320193"); // single-CIK cap per ruling
        EnsureFilingsService svc = new EnsureFilingsService(client, jdbc);
        Map<String, Object> res = svc.ensureFilings(cik, "(smoke)");
        System.out.println("[flamingo] LIVE smoke result: " + res);

        JsonNode facts = client.companyFacts(cik);
        System.out.println("[flamingo] companyfacts namespaces: "
                + (facts.has("facts") ? facts.get("facts").size() : 0)
                + " (raw persisted by client pre-parse)");
    }
}
