package com.nousresearch.hermes.tenant.sandbox;

import com.nousresearch.hermes.common.HermesProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory for selecting the appropriate RateLimitStore implementation
 * based on the deployment profile.
 *
 * <ul>
 *   <li>{@code local} profile -> {@link LocalRateLimitStore}</li>
 *   <li>{@code cluster} profile -> {@link RedisRateLimitStore} (if RedisOps available)</li>
 * </ul>
 */
public class RateLimitStoreFactory {
    private static final Logger logger = LoggerFactory.getLogger(RateLimitStoreFactory.class);

    public static RateLimitStore create(HermesProfile profile) {
        if (profile.isCluster() && profile.hasRedis()) {
            logger.info("Using Redis-backed rate limiter");
            return new RedisRateLimitStore(profile.redisOps());
        }
        if (profile.isCluster()) {
            logger.warn("Cluster profile but no RedisOps configured; falling back to local rate limiter");
        }
        return new LocalRateLimitStore();
    }
}
