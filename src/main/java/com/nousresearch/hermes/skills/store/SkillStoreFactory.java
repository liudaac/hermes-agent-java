package com.nousresearch.hermes.skills.store;

import com.nousresearch.hermes.common.HermesProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory for {@link SkillStore} implementations.
 *
 * <p>Follows the same pattern as {@link com.nousresearch.hermes.tenant.quota.QuotaStoreFactory}:</p>
 * <ul>
 *   <li>LOCAL mode &rarr; {@link LocalSkillStore}</li>
 *   <li>CLUSTER mode + Redis &rarr; RedisSkillStore with pub/sub broadcast (Sprint B)</li>
 *   <li>CLUSTER mode + Postgres &rarr; PostgresSkillStore with version persistence (Sprint C)</li>
 * </ul>
 */
public class SkillStoreFactory {

    private static final Logger logger = LoggerFactory.getLogger(SkillStoreFactory.class);

    private static volatile SkillStore instance;

    /**
     * Get the singleton SkillStore instance.
     */
    public static SkillStore get() {
        if (instance == null) {
            synchronized (SkillStoreFactory.class) {
                if (instance == null) {
                    instance = create();
                }
            }
        }
        return instance;
    }

    /**
     * Override the instance (for testing).
     */
    public static void set(SkillStore store) {
        instance = store;
    }

    /**
     * Reset to null (for testing).
     */
    public static void reset() {
        instance = null;
    }

    private static SkillStore create() {
        HermesProfile profile = HermesProfile.current();

        if (profile != null && profile.hasRedis()) {
            // Sprint B: RedisSkillStore with pub/sub
            logger.info("CLUSTER mode detected, but Redis implementation not yet available. " +
                        "Falling back to LocalSkillStore.");
        }

        logger.info("Using LocalSkillStore");
        return new LocalSkillStore();
    }
}
