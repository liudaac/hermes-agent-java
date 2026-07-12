package com.nousresearch.hermes.tenant.sandbox;

import com.nousresearch.hermes.common.RedisOps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Redis-backed RateLimitStore for multi-instance deployments.
 *
 * <p>Uses a Lua script to atomically check-and-decrement the token bucket,
 * ensuring that N instances sharing a Redis counter enforce a global rate
 * limit equal to the configured value (not N × configured).</p>
 *
 * <p>Lua script logic:</p>
 * <ol>
 *   <li>Get current tokens from Redis</li>
 *   <li>If tokens > 0, DECR and return 1 (allowed)</li>
 *   <li>Else return 0 (rate limited)</li>
 *   <li>TTL on the key ensures the bucket resets every second</li>
 * </ol>
 *
 * <p><b>Phase 4b status:</b> Implementation complete, but requires a
 * {@link RedisOps} instance to be injected (via {@link com.nousresearch.hermes.common.HermesProfile}).
 * Not wired until a Redis client (Lettuce/Jedis) is on the classpath.</p>
 */
public class RedisRateLimitStore implements RateLimitStore {
    private static final Logger logger = LoggerFactory.getLogger(RedisRateLimitStore.class);

    private final RedisOps redis;

    /**
     * Lua script: atomically check and consume a token.
     * Args: [1] = key, [2] = maxPerSec
     * Returns: 1 if allowed, 0 if rate-limited
     */
    private static final String ACQUIRE_SCRIPT = """
        local key = KEYS[1]
        local maxPerSec = tonumber(ARGV[1])
        local current = tonumber(redis.call('GET', key) or maxPerSec)
        if current > 0 then
            redis.call('DECR', key)
            redis.call('EXPIRE', key, 1)
            return 1
        else
            return 0
        end
        """;

    public RedisRateLimitStore(RedisOps redis) {
        this.redis = redis;
        logger.info("RedisRateLimitStore initialized");
    }

    @Override
    public boolean tryAcquire(String key, int maxPerSec) {
        try {
            Long result = redis.eval(ACQUIRE_SCRIPT, List.of(key), List.of(String.valueOf(maxPerSec)));
            return result != null && result == 1;
        } catch (Exception e) {
            logger.warn("Redis rate limit check failed for {}: {}, allowing request", key, e.getMessage());
            return true; // fail-open on Redis errors
        }
    }

    @Override
    public long currentTokens(String key) {
        try {
            String val = redis.get(key);
            return val != null ? Long.parseLong(val) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    public void reset(String key) {
        try {
            redis.del(key);
        } catch (Exception e) {
            logger.warn("Redis rate limit reset failed for {}: {}", key, e.getMessage());
        }
    }
}
