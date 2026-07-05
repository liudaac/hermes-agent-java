package com.nousresearch.hermes.business.approval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * S2-1 #3: Redis 审批存储（多实例模式）。
 *
 * <p>使用 Redis Hash 存储审批状态，Pub/Sub 跨实例通知。</p>
 *
 * <p>Redis 数据结构：</p>
 * <ul>
 *   <li>Hash: {@code approval:<id>} → {nodeId, type, operation, status}</li>
 *   <li>Pub/Sub channel: {@code approval:results:<id>} → {approved, reason}</li>
 *   <li>TTL: 300s（5 分钟，对齐 ApprovalSystem 超时）</li>
 * </ul>
 *
 * <p>跨实例流程：</p>
 * <ol>
 *   <li>节点 A：storePending(id, nodeA, ...) + subscribe(id, callback)</li>
 *   <li>节点 B：收到审批回调 → resolveNode(id) → nodeA</li>
 *   <li>节点 B：publishResult(id, true, ...) → PUBLISH 到 approval:results:id</li>
 *   <li>节点 A：SUBSCRIBE 收到消息 → 触发 callback.onResult(true)</li>
 *   <li>节点 A：complete(id) 清理</li>
 * </ol>
 */
public class RedisApprovalStore implements ApprovalStore {
    private static final Logger logger = LoggerFactory.getLogger(RedisApprovalStore.class);

    private final String localNodeId;
    private final RedisOps redis;
    private final Map<String, ApprovalResultCallback> localCallbacks = new ConcurrentHashMap<>();

    /** Redis key 前缀 */
    private static final String KEY_PREFIX = "approval:";
    private static final String CHANNEL_PREFIX = "approval:results:";
    private static final int TTL_SECONDS = 300;

    public RedisApprovalStore(String localNodeId, RedisOps redis) {
        this.localNodeId = localNodeId;
        this.redis = redis;
        // 订阅本节点关心的 channel
        redis.subscribePattern(CHANNEL_PREFIX + "*", (channel, message) -> {
            // channel = "approval:results:<id>"
            String approvalId = channel.substring(CHANNEL_PREFIX.length());
            ApprovalResultCallback callback = localCallbacks.get(approvalId);
            if (callback != null) {
                // 解析消息: "approved|reason" 或 "denied|reason"
                String[] parts = message.split("\\|", 2);
                boolean approved = "approved".equals(parts[0]);
                String reason = parts.length > 1 ? parts[1] : "";
                logger.info("Received approval result via Pub/Sub: id={}, approved={}", approvalId, approved);
                callback.onResult(approved, reason);
            }
        });
    }

    @Override
    public void storePending(String approvalId, String nodeId, String approvalType, String operation) {
        String key = KEY_PREFIX + approvalId;
        redis.hset(key, "nodeId", nodeId);
        redis.hset(key, "type", approvalType);
        redis.hset(key, "operation", operation);
        redis.hset(key, "status", "pending");
        redis.expire(key, TTL_SECONDS);
        logger.debug("Stored pending approval in Redis: id={}, node={}", approvalId, nodeId);
    }

    @Override
    public String resolveNode(String approvalId) {
        String key = KEY_PREFIX + approvalId;
        String nodeId = redis.hget(key, "nodeId");
        if (nodeId == null) {
            // 可能本地有（fallback 兼容）
            logger.debug("Approval not found in Redis: {}", approvalId);
        }
        return nodeId;
    }

    @Override
    public void publishResult(String approvalId, boolean approved, String reason) {
        String channel = CHANNEL_PREFIX + approvalId;
        String message = (approved ? "approved" : "denied") + "|" + (reason != null ? reason : "");

        // 更新 Redis 中的状态
        String key = KEY_PREFIX + approvalId;
        redis.hset(key, "status", approved ? "approved" : "denied");

        // 发布通知（所有订阅者收到，但只有发起节点有 callback）
        redis.publish(channel, message);
        logger.info("Published approval result: id={}, approved={}", approvalId, approved);
    }

    @Override
    public void subscribe(String approvalId, ApprovalResultCallback callback) {
        localCallbacks.put(approvalId, callback);
        logger.debug("Subscribed to approval: id={}", approvalId);
    }

    @Override
    public void unsubscribe(String approvalId) {
        localCallbacks.remove(approvalId);
    }

    @Override
    public void complete(String approvalId) {
        String key = KEY_PREFIX + approvalId;
        redis.del(key);
        localCallbacks.remove(approvalId);
        logger.debug("Completed approval: id={}", approvalId);
    }

    @Override
    public int pendingCount() {
        // 本地等待回调的数量
        return localCallbacks.size();
    }

    /**
     * Redis 操作接口（与具体客户端解耦）。
     */
    public interface RedisOps {
        void hset(String key, String field, String value);
        String hget(String key, String field);
        void expire(String key, int seconds);
        void del(String key);
        void publish(String channel, String message);
        void subscribePattern(String pattern, BiConsumer<String, String> listener);
    }

    @FunctionalInterface
    public interface BiConsumer<T, U> {
        void accept(T t, U u);
    }
}
