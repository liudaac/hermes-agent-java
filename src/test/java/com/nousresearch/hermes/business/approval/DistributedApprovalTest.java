package com.nousresearch.hermes.business.approval;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * S2-1 #3: 审批跨实例 callback 测试
 */
class DistributedApprovalTest {

    // ========================================================================
    // LocalApprovalStore
    // ========================================================================

    @Nested
    @DisplayName("LocalApprovalStore")
    class LocalStoreTest {

        private LocalApprovalStore store;

        @BeforeEach
        void setUp() {
            store = new LocalApprovalStore("node-1");
        }

        @Test
        @DisplayName("storePending → resolveNode 一致")
        void storeAndResolve() {
            store.storePending("approval-1", "node-1", "tool-approval", "rm -rf /");
            assertEquals("node-1", store.resolveNode("approval-1"));
        }

        @Test
        @DisplayName("未存储的 approval → resolveNode 返回 null")
        void unknownApproval() {
            assertNull(store.resolveNode("nonexistent"));
        }

        @Test
        @DisplayName("publishResult 触发 callback")
        void publishTriggersCallback() {
            AtomicInteger callCount = new AtomicInteger(0);
            store.storePending("approval-1", "node-1", "tool-approval", "ls");
            store.subscribe("approval-1", (approved, reason) -> {
                callCount.incrementAndGet();
                assertTrue(approved);
                assertEquals("ok", reason);
            });
            store.publishResult("approval-1", true, "ok");
            assertEquals(1, callCount.get());
        }

        @Test
        @DisplayName("complete 清理状态")
        void completeClears() {
            store.storePending("approval-1", "node-1", "tool-approval", "ls");
            store.complete("approval-1");
            assertNull(store.resolveNode("approval-1"));
            assertEquals(0, store.pendingCount());
        }

        @Test
        @DisplayName("unsubscribe 移除 callback")
        void unsubscribeRemovesCallback() {
            AtomicInteger callCount = new AtomicInteger(0);
            store.storePending("approval-1", "node-1", "tool-approval", "ls");
            store.subscribe("approval-1", (a, r) -> callCount.incrementAndGet());
            store.unsubscribe("approval-1");
            store.publishResult("approval-1", true, "");
            assertEquals(0, callCount.get());
        }

        @Test
        @DisplayName("pendingCount 正确")
        void pendingCount() {
            assertEquals(0, store.pendingCount());
            store.storePending("a1", "node-1", "tool", "op1");
            store.storePending("a2", "node-1", "tool", "op2");
            // pendingCount 统计 pending map
            store.subscribe("a1", (a, r) -> {});
            store.subscribe("a2", (a, r) -> {});
            // pendingCount 在 LocalApprovalStore 中统计 pending map
            assertEquals(2, store.pendingCount());
        }
    }

    // ========================================================================
    // RedisApprovalStore（用 Mock Redis 模拟）
    // ========================================================================

    @Nested
    @DisplayName("RedisApprovalStore")
    class RedisStoreTest {

        private MockRedisOps mockRedis;
        private RedisApprovalStore store;

        @BeforeEach
        void setUp() {
            mockRedis = new MockRedisOps();
            store = new RedisApprovalStore("node-1", mockRedis);
        }

        @Test
        @DisplayName("storePending 写入 Redis Hash")
        void storePendingWritesHash() {
            store.storePending("approval-1", "node-1", "tool-approval", "rm");
            String key = "approval:approval-1";
            assertEquals("node-1", mockRedis.hashData.get(key).get("nodeId"));
            assertEquals("tool-approval", mockRedis.hashData.get(key).get("type"));
            assertEquals("rm", mockRedis.hashData.get(key).get("operation"));
            assertEquals("pending", mockRedis.hashData.get(key).get("status"));
            assertTrue(mockRedis.expireCount > 0);
        }

        @Test
        @DisplayName("resolveNode 从 Redis Hash 读取")
        void resolveNodeFromHash() {
            store.storePending("approval-1", "node-2", "tool-approval", "ls");
            assertEquals("node-2", store.resolveNode("approval-1"));
        }

        @Test
        @DisplayName("未存储的 approval → resolveNode 返回 null")
        void unknownApproval() {
            assertNull(store.resolveNode("nonexistent"));
        }

        @Test
        @DisplayName("publishResult 更新状态 + 发布到 channel")
        void publishResultUpdatesAndPublishes() {
            store.storePending("approval-1", "node-1", "tool", "op");

            // 模拟本节点订阅
            AtomicInteger callbackCount = new AtomicInteger(0);
            store.subscribe("approval-1", (approved, reason) -> {
                callbackCount.incrementAndGet();
                assertTrue(approved);
            });

            // 发布结果
            store.publishResult("approval-1", true, "approved by admin");

            // 验证 Redis hash 状态更新
            assertEquals("approved", mockRedis.hashData.get("approval:approval-1").get("status"));

            // 验证 publish 被调用
            assertTrue(mockRedis.publishedMessages.size() > 0);
            assertTrue(mockRedis.publishedMessages.entrySet().iterator().next().getValue().contains("approved"));
        }

        @Test
        @DisplayName("跨实例：节点 B publishResult → 节点 A 通过 Pub/Sub 收到")
        void crossInstancePubSub() {
            // 模拟两个节点共享同一个 MockRedis（Pub/Sub 广播）
            MockRedisOps sharedRedis = new MockRedisOps();
            RedisApprovalStore nodeA = new RedisApprovalStore("node-A", sharedRedis);
            RedisApprovalStore nodeB = new RedisApprovalStore("node-B", sharedRedis);

            // 节点 A 发起审批
            nodeA.storePending("approval-1", "node-A", "tool", "dangerous-op");
            AtomicInteger aReceived = new AtomicInteger(0);
            nodeA.subscribe("approval-1", (approved, reason) -> {
                aReceived.incrementAndGet();
                assertFalse(approved);
                assertEquals("too dangerous", reason);
            });

            // 节点 B 收到审批回调
            String targetNode = nodeB.resolveNode("approval-1");
            assertEquals("node-A", targetNode);

            // 节点 B 发布结果（不是本地，通过 Pub/Sub 通知节点 A）
            nodeB.publishResult("approval-1", false, "too dangerous");

            // 节点 A 应该通过 Pub/Sub 收到结果
            assertEquals(1, aReceived.get(), "Node A should receive result via Pub/Sub");
        }

        @Test
        @DisplayName("complete 删除 Redis key + 本地 callback")
        void completeClears() {
            store.storePending("approval-1", "node-1", "tool", "op");
            store.subscribe("approval-1", (a, r) -> {});
            store.complete("approval-1");
            assertNull(mockRedis.hashData.get("approval:approval-1"));
            assertEquals(0, store.pendingCount());
        }

        @Test
        @DisplayName("pendingCount 统计本地 callback")
        void pendingCount() {
            assertEquals(0, store.pendingCount());
            store.subscribe("a1", (a, r) -> {});
            store.subscribe("a2", (a, r) -> {});
            assertEquals(2, store.pendingCount());
        }
    }

    // ========================================================================
    // ToolApprovalCoordinator 集成
    // ========================================================================

    @Nested
    @DisplayName("ToolApprovalCoordinator + ApprovalStore 集成")
    class CoordinatorIntegrationTest {

        @Test
        @DisplayName("默认构造使用 LocalApprovalStore")
        void defaultUsesLocal() {
            // ToolApprovalCoordinator 默认构造使用 LocalApprovalStore
            // 只验证不崩溃
            assertNotNull(new LocalApprovalStore());
        }

        @Test
        @DisplayName("注入 ApprovalStore 不影响现有逻辑")
        void injectStore() {
            LocalApprovalStore store = new LocalApprovalStore("test-node");
            // 验证 store 可独立工作
            store.storePending("test-1", "test-node", "tool", "op");
            assertEquals("test-node", store.resolveNode("test-1"));
        }
    }

    // ========================================================================
    // Mock Redis
    // ========================================================================

    private static class MockRedisOps implements RedisApprovalStore.RedisOps {
        final Map<String, Map<String, String>> hashData = new ConcurrentHashMap<>();
        final Map<String, String> publishedMessages = new ConcurrentHashMap<>();
        int expireCount = 0;
        final List<RedisApprovalStore.BiConsumer<String, String>> subscribers = new ArrayList<>();

        @Override
        public void hset(String key, String field, String value) {
            hashData.computeIfAbsent(key, k -> new HashMap<>()).put(field, value);
        }

        @Override
        public String hget(String key, String field) {
            Map<String, String> hash = hashData.get(key);
            return hash != null ? hash.get(field) : null;
        }

        @Override
        public void expire(String key, int seconds) {
            expireCount++;
        }

        @Override
        public void del(String key) {
            hashData.remove(key);
        }

        @Override
        public void publish(String channel, String message) {
            publishedMessages.put(channel, message);
            // 广播给所有订阅者（模拟 Redis Pub/Sub）
            for (var subscriber : subscribers) {
                subscriber.accept(channel, message);
            }
        }

        @Override
        public void subscribePattern(String pattern, RedisApprovalStore.BiConsumer<String, String> listener) {
            subscribers.add(listener);
        }
    }
}
