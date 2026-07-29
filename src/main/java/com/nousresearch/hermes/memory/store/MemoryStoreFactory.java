package com.nousresearch.hermes.memory.store;

import com.nousresearch.hermes.common.HermesProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory for {@link MemoryStore} implementations.
 *
 * <p>Follows the same pattern as {@link com.nousresearch.hermes.tenant.quota.QuotaStoreFactory}
 * and {@link com.nousresearch.hermes.tenant.sandbox.RateLimitStoreFactory}:</p>
 * <ul>
 *   <li>LOCAL mode &rarr; {@link LocalMemoryStore}</li>
 *   <li>CLUSTER mode + Redis &rarr; RedisMemoryStore (Sprint B)</li>
 *   <li>CLUSTER mode + Redis + Postgres &rarr; Hybrid (Sprint C)</li>
 * </ul>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * MemoryStore store = MemoryStoreFactory.get();
 * store.appendSessionMessage(tenantId, sessionId, "user", "Hello");
 * }</pre>
 */
public class MemoryStoreFactory {

    private static final Logger logger = LoggerFactory.getLogger(MemoryStoreFactory.class);

    private static volatile MemoryStore instance;

    /**
     * Get the singleton MemoryStore instance.
     */
    public static MemoryStore get() {
        if (instance == null) {
            synchronized (MemoryStoreFactory.class) {
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
    public static void set(MemoryStore store) {
        instance = store;
    }

    /**
     * Reset to null (for testing).
     */
    public static void reset() {
        instance = null;
    }

    private static MemoryStore create() {
        HermesProfile profile = HermesProfile.current();

        if (profile != null && profile.hasRedis()) {
            if (profile.hasPostgres()) {
                // Sprint C: Hybrid (Redis short-term + Postgres long-term)
                logger.info("CLUSTER mode: using PostgresMemoryStore (long-term persistence)");
                return new PostgresMemoryStore(getDataSource());
            }
            logger.info("CLUSTER mode: using RedisMemoryStore");
            return new RedisMemoryStore(profile.redisOps());
        }

        if (profile != null && profile.hasPostgres()) {
            logger.info("Postgres-only mode: using PostgresMemoryStore");
            return new PostgresMemoryStore(getDataSource());
        }

        logger.info("Using LocalMemoryStore");
        return new LocalMemoryStore();
    }

    private static javax.sql.DataSource getDataSource() {
        return com.nousresearch.hermes.config.repository.DataSourceFactory.create();
    }
}
