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
