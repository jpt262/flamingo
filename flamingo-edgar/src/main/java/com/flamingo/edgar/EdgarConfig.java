package com.flamingo.edgar;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Immutable EDGAR client configuration.
 *
 * <p>SEC contract (build order §6): every request carries a declared User-Agent
 * with contact mailbox; undisclosed UAs receive HTTP 403. The UA string is
 * supplied via {@code SEC_USER_AGENT} (real mailbox, never committed — §17).</p>
 *
 * @param userAgent          mandated identifying UA header value
 * @param rawStorePath       root of the raw-first object store (R1)
 * @param requestsPerSecond  token-bucket refill rate (contract ceiling is 5)
 * @param maxAttempts        total attempts per call including the first
 * @param baseBackoff        exponential base delay; grows x2^attempt + jitter
 */
public record EdgarConfig(
        String userAgent,
        Path rawStorePath,
        double requestsPerSecond,
        int maxAttempts,
        Duration baseBackoff,
        String dataBaseUrl) {

    /** Contract-published identity used only as a last-resort fallback so a
     *  missing env var fails loudly at first response rather than silently 403ing. */
    static final String DEFAULT_UA = "Flamingo Research <ops@flamingo.example>";

    public static final String PROD_DATA_BASE = "https://data.sec.gov";
    public static final int MAX_CONCURRENT_CONTRACT = 3;
    public static final double MAX_REQUESTS_PER_SECOND_CONTRACT = 5.0;

    public EdgarConfig {
        if (userAgent == null || userAgent.isBlank()) {
            throw new IllegalArgumentException("userAgent must not be blank");
        }
        if (requestsPerSecond <= 0 || requestsPerSecond > MAX_REQUESTS_PER_SECOND_CONTRACT) {
            throw new IllegalArgumentException(
                    "requestsPerSecond must be in (0, %s]".formatted(MAX_REQUESTS_PER_SECOND_CONTRACT));
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        if (dataBaseUrl == null || dataBaseUrl.isBlank()) {
            dataBaseUrl = PROD_DATA_BASE;
        }
    }

    /** Convenience constructor: production EDGAR hosts. */
    public EdgarConfig(String userAgent, Path rawStorePath, double requestsPerSecond,
                       int maxAttempts, Duration baseBackoff) {
        this(userAgent, rawStorePath, requestsPerSecond, maxAttempts, baseBackoff, PROD_DATA_BASE);
    }

    public static EdgarConfig defaults(Path rawStorePath) {
        return new EdgarConfig(DEFAULT_UA, rawStorePath, 4.0, 4, Duration.ofMillis(500));
    }

    /** Environment-driven construction: SEC_USER_AGENT, FLAMINGO_RAW_STORE, FLAMINGO_RPS. */
    public static EdgarConfig fromEnv() {
        String ua = System.getenv("SEC_USER_AGENT");
        String store = System.getenv("FLAMINGO_RAW_STORE");
        String rps = System.getenv("FLAMINGO_RPS");
        return new EdgarConfig(
                ua != null && !ua.isBlank() ? ua : DEFAULT_UA,
                Path.of(store != null && !store.isBlank() ? store : ".data/rawstore"),
                rps != null ? Double.parseDouble(rps) : 4.0,
                4,
                Duration.ofMillis(500));
    }
}
