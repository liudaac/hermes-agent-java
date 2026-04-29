package com.nousresearch.hermes.tenant.persistence;

import com.nousresearch.hermes.tenant.core.TenantContext;
import com.nousresearch.hermes.tenant.core.TenantState;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * 租户状态持久化仓库接口
 * 
 * 定义租户状态存储和恢复的标准接口，支持多种后端实现：
 * - 文件系统（默认）
 * - PostgreSQL
 * - Redis
 * - MongoDB
 */
public interface TenantStateRepository {

    /**
     * 保存租户状态
     * 
     * @param tenantId 租户ID
     * @param state 状态快照
     * @return 完成后的 Future
     */
    CompletableFuture<Void> saveState(String tenantId, TenantStateSnapshot state);

    /**
     * 加载租户状态
     * 
     * @param tenantId 租户ID
     * @return 状态快照（如果不存在则返回空）
     */
    CompletableFuture<Optional<TenantStateSnapshot>> loadState(String tenantId);

    /**
     * 删除租户状态
     * 
     * @param tenantId 租户ID
     * @return 完成后的 Future
     */
    CompletableFuture<Void> deleteState(String tenantId);

    /**
     * 列出所有已持久化的租户
     * 
     * @return 租户ID列表
     */
    CompletableFuture<java.util.List<String>> listTenants();

    /**
     * 检查租户状态是否存在
     * 
     * @param tenantId 租户ID
     * @return 是否存在
     */
    CompletableFuture<Boolean> exists(String tenantId);

    /**
     * 获取最后更新时间
     * 
     * @param tenantId 租户ID
     * @return 最后更新时间
     */
    CompletableFuture<Optional<Instant>> getLastUpdated(String tenantId);

    /**
     * 保存会话状态
     * 
     * @param tenantId 租户ID
     * @param sessionId 会话ID
     * @param sessionState 会话状态
     * @return 完成后的 Future
     */
    CompletableFuture<Void> saveSession(String tenantId, String sessionId, SessionState sessionState);

    /**
     * 加载会话状态
     * 
     * @param tenantId 租户ID
     * @param sessionId 会话ID
     * @return 会话状态
     */
    CompletableFuture<Optional<SessionState>> loadSession(String tenantId, String sessionId);

    /**
     * 列出所有会话
     * 
     * @param tenantId 租户ID
     * @return 会话ID列表
     */
    CompletableFuture<java.util.List<String>> listSessions(String tenantId);

    /**
     * 删除会话
     * 
     * @param tenantId 租户ID
     * @param sessionId 会话ID
     * @return 完成后的 Future
     */
    CompletableFuture<Void> deleteSession(String tenantId, String sessionId);

    /**
     * 关闭仓库
     */
    void close();

    // ============ 数据模型 ============

    /**
     * 租户状态快照
     */
    record TenantStateSnapshot(
        String tenantId,
        TenantContext.State state,
        Instant createdAt,
        Instant lastActivity,
        Map<String, Object> config,
        Map<String, Object> quota,
        Map<String, Object> securityPolicy,
        long version  // 乐观锁版本
    ) {
        public TenantStateSnapshot {
            config = config != null ? Map.copyOf(config) : Map.of();
            quota = quota != null ? Map.copyOf(quota) : Map.of();
            securityPolicy = securityPolicy != null ? Map.copyOf(securityPolicy) : Map.of();
        }
    }

    /**
     * 会话状态
     */
    record SessionState(
        String sessionId,
        String tenantId,
        Instant createdAt,
        Instant lastActivity,
        Map<String, Object> metadata,
        byte[] serializedContext  // 序列化的会话上下文
    ) {
        public SessionState {
            metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
        }
    }
}
