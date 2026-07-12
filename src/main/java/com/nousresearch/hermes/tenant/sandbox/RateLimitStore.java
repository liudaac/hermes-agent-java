package com.nousresearch.hermes.tenant.sandbox;

/**
 * Rate limit store abstraction.
 *
 * <p>Allows the rate limiter to be backed by either local memory
 * ({@link LocalRateLimitStore}) or Redis ({@link RedisRateLimitStore})
 * for cross-instance rate enforcement.</p>
 *
 * <p>In cluster mode, all instances share the same Redis counter,
 * so the aggregate rate across N instances equals the configured limit.
 * In local mode, each instance has its own counter, so the effective
 * limit is N × configured (acceptable for single-instance deployments).</p>
 */
public interface RateLimitStore {

    /**
     * Atomically try to consume 1 token from the bucket.
     *
     * @param key       rate limit key (e.g. "tenant:acme:api")
     * @param maxPerSec max tokens per second
     * @return true if a token was consumed (request allowed), false if rate limited
     */
    boolean tryAcquire(String key, int maxPerSec);

    /**
     * Get current token count for the bucket.
     *
     * @param key rate limit key
     * @return current available tokens (approximate)
     */
    long currentTokens(String key);

    /**
     * Reset the bucket (e.g. on config change).
     *
     * @param key rate limit key
     */
    void reset(String key);
}
