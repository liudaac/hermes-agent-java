package com.nousresearch.hermes.tenant.sandbox;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory RateLimitStore using per-key token buckets.
 *
 * <p>Single-instance default. For multi-instance, switch to
 * {@link RedisRateLimitStore} via {@code hermes.profile=cluster}.</p>
 */
public class LocalRateLimitStore implements RateLimitStore {

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    @Override
    public boolean tryAcquire(String key, int maxPerSec) {
        Bucket b = buckets.computeIfAbsent(key, k -> new Bucket(maxPerSec));
        // If maxPerSec changed (config update), recreate bucket.
        if (b.maxPerSec != maxPerSec) {
            b = new Bucket(maxPerSec);
            buckets.put(key, b);
        }
        return b.tryAcquire();
    }

    @Override
    public long currentTokens(String key) {
        Bucket b = buckets.get(key);
        return b != null ? b.tokens.get() : 0;
    }

    @Override
    public void reset(String key) {
        buckets.remove(key);
    }

    /** Simple token bucket with nanosecond refill. */
    private static final class Bucket {
        final int maxPerSec;
        final AtomicLong tokens;
        final AtomicLong lastRefillNanos;
        final long refillIntervalNanos;

        Bucket(int maxPerSec) {
            this.maxPerSec = maxPerSec;
            this.tokens = new AtomicLong(maxPerSec);
            this.lastRefillNanos = new AtomicLong(System.nanoTime());
            this.refillIntervalNanos = maxPerSec > 0
                ? 1_000_000_000L / maxPerSec
                : Long.MAX_VALUE;
        }

        boolean tryAcquire() {
            refill();
            long current = tokens.get();
            if (current > 0) {
                return tokens.compareAndSet(current, current - 1);
            }
            return false;
        }

        void refill() {
            long now = System.nanoTime();
            long last = lastRefillNanos.get();
            long elapsed = now - last;
            if (elapsed < refillIntervalNanos) return;

            long tokensToAdd = elapsed / refillIntervalNanos;
            if (tokensToAdd > 0) {
                long newLast = last + tokensToAdd * refillIntervalNanos;
                if (lastRefillNanos.compareAndSet(last, newLast)) {
                    long current, next;
                    do {
                        current = tokens.get();
                        next = Math.min(maxPerSec, current + tokensToAdd);
                    } while (!tokens.compareAndSet(current, next));
                }
            }
        }
    }
}
