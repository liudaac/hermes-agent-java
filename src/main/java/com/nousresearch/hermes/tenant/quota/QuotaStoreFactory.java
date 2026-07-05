package com.nousresearch.hermes.tenant.quota;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * S2-1 #1: 根据 config 选择 QuotaStore 实现。
 *
 * <p>配置示例：</p>
 * <pre>{@code
 * quota:
 *   store: local    # 或 redis
 *   redis:
 *     url: redis://localhost:6379
 * }</pre>
 */
public class QuotaStoreFactory {
    private static final Logger logger = LoggerFactory.getLogger(QuotaStoreFactory.class);

    /**
     * 创建 QuotaStore。
     *
     * @param tenantId 租户 ID
     * @param storeType "local" 或 "redis"
     * @param redisExecutor Redis 命令执行器（storeType="redis" 时必需，可为 null）
     * @return QuotaStore 实例
     */
    public static QuotaStore create(String tenantId, String storeType,
                                    RedisQuotaStore.RedisCommandExecutor redisExecutor) {
        if ("redis".equalsIgnoreCase(storeType)) {
            if (redisExecutor == null) {
                logger.warn("Redis quota store requested but no executor provided, falling back to local");
                return new LocalQuotaStore();
            }
            logger.info("Using Redis quota store for tenant: {}", tenantId);
            return new RedisQuotaStore(tenantId, redisExecutor);
        }

        logger.debug("Using local quota store for tenant: {}", tenantId);
        return new LocalQuotaStore();
    }

    /**
     * 创建默认的 LocalQuotaStore。
     */
    public static QuotaStore createLocal() {
        return new LocalQuotaStore();
    }
}
