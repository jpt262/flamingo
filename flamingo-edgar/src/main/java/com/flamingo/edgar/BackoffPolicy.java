package com.flamingo.edgar;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * Exponential backoff with jitter for transient failures. Per §6, HTTP 403 and
 * 503 are retried with exponential backoff + jitter; 5xx generally too; 4xx
 * elsewhere are permanent (fail fast, no blind hammering).
 */
final class BackoffPolicy {

    private final int maxAttempts;
    private final Duration base;
    private final RandomGenerator random;

    BackoffPolicy(int maxAttempts, Duration base) {
        this.maxAttempts = maxAttempts;
        this.base = base;
        this.random = RandomGenerator.getDefault();
    }

    boolean shouldRetry(HttpResponse<?> response) {
        int s = response.statusCode();
        return s == 403 || s == 429 || s >= 500;
    }

    boolean exhausted(int attemptSoFar) {
        return attemptSoFar >= maxAttempts;
    }

    /** Sleeps the appropriate delay for the just-failed attempt (1-based). */
    void waitBeforeRetry(int failedAttempt, HttpResponse<?> response) {
        Duration d = delayFor(failedAttempt, response);
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted during backoff", e);
        }
    }

    Duration delayFor(int failedAttempt, HttpResponse<?> response) {
        Optional<Duration> retryAfter = retryAfter(response);
        if (retryAfter.isPresent()) {
            return retryAfter.get();
        }
        long ms = base.toMillis() << Math.min(failedAttempt - 1, 6); // cap shift blowup
        long jitter = random.nextLong(0, Math.max(1, base.toMillis() / 2));
        return Duration.ofMillis(ms + jitter);
    }

    private Optional<Duration> retryAfter(HttpResponse<?> response) {
        String v = response.headers().firstValue("Retry-After").orElse(null);
        if (v == null || v.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Duration.ofSeconds(Long.parseLong(v.trim())));
        } catch (NumberFormatException e) {
            return Optional.empty(); // HTTP-date form unsupported: fall through to exp backoff
        }
    }
}
