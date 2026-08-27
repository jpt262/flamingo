package com.flamingo.edgar;

import java.time.Duration;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Token-bucket rate limiter, thread-safe. Refill is computed from elapsed real
 * time so bursts cannot exceed the configured sustained rate (§6 ceiling: 5/s,
 * configured lower by default to stay polite).
 */
final class TokenBucket {

    private final double capacityPerSecond;
    private final ReentrantLock lock = new ReentrantLock();
    private double tokens;
    private long lastRefillNanos;

    TokenBucket(double requestsPerSecond) {
        this.capacityPerSecond = requestsPerSecond;
        this.tokens = requestsPerSecond;
        this.lastRefillNanos = System.nanoTime();
    }

    /** Blocks until one token is available, then consumes it. */
    void acquire() {
        while (true) {
            long sleepNanos = 0;
            lock.lock();
            try {
                refill();
                if (tokens >= 1.0) {
                    tokens -= 1.0;
                    return;
                }
                sleepNanos = (long) Math.ceil((1.0 - tokens) / capacityPerSecond * 1_000_000_000.0);
            } finally {
                lock.unlock();
            }
            try {
                Thread.sleep(Duration.ofNanos(Math.max(sleepNanos, 1_000_000)).toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while awaiting rate-limit token", e);
            }
        }
    }

    private void refill() {
        long now = System.nanoTime();
        double elapsedSeconds = (now - lastRefillNanos) / 1_000_000_000.0;
        tokens = Math.min(capacityPerSecond, tokens + elapsedSeconds * capacityPerSecond);
        lastRefillNanos = now;
    }
}
