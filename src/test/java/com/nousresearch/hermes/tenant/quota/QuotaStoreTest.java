package com.nousresearch.hermes.tenant.quota;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * S2-1 #1: 配额存储抽象 + Redis/Local 双实现测试
 */
class QuotaStoreTest {

    // ========================================================================
    // LocalQuotaStore
    // ========================================================================

    @Nested
    @DisplayName("LocalQuotaStore")
    class LocalStoreTest {

        private LocalQuotaStore store;

        @BeforeEach
        void setUp() {
            store = new LocalQuotaStore();
        }

        @Test
        @DisplayName("incrementAndGetDailyRequests 原子自增")
        void incrementRequests() {
            assertEquals(1, store.incrementAndGetDailyRequests());
            assertEquals(2, store.incrementAndGetDailyRequests());
            assertEquals(3, store.incrementAndGetDailyRequests());
        }

        @Test
        @DisplayName("decrementDailyRequests 回滚")
        void decrementRequests() {
            store.incrementAndGetDailyRequests();
            store.incrementAndGetDailyRequests();
            store.decrementDailyRequests();
            assertEquals(1, store.getDailyRequests());
        }

        @Test
        @DisplayName("addAndGetDailyTokens 原子加")
        void addTokens() {
            assertEquals(100, store.addAndGetDailyTokens(100));
            assertEquals(250, store.addAndGetDailyTokens(150));
        }

        @Test
        @DisplayName("subtractDailyTokens 回滚")
        void subtractTokens() {
            store.addAndGetDailyTokens(500);
            store.subtractDailyTokens(200);
            assertEquals(300, store.getDailyTokens());
        }

        @Test
        @DisplayName("activeAgents 自增自减")
        void activeAgents() {
            store.incrementActiveAgents();
            store.incrementActiveAgents();
            assertEquals(2, store.getActiveAgents());
            store.decrementActiveAgents();
            assertEquals(1, store.getActiveAgents());
        }

        @Test
        @DisplayName("storageUsage 加减")
        void storageUsage() {
            store.addAndGetStorageUsage(1024);
            store.addAndGetStorageUsage(2048);
            assertEquals(3072, store.getStorageUsage());
            store.subtractStorageUsage(1024);
            assertEquals(2048, store.getStorageUsage());
        }

        @Test
        @DisplayName("初始值为 0")
        void initialValues() {
            assertEquals(0, store.getDailyRequests());
            assertEquals(0, store.getDailyTokens());
            assertEquals(0, store.getActiveAgents());
            assertEquals(0, store.getStorageUsage());
        }

        @Test
        @DisplayName("checkAndResetDaily 同日不重置")
        void sameDayNoReset() {
            store.incrementAndGetDailyRequests();
            assertFalse(store.checkAndResetDaily());
            assertEquals(1, store.getDailyRequests());
        }
    }

    // ========================================================================
    // RedisQuotaStore（用 Mock 执行器模拟 Redis）
    // ========================================================================

    @Nested
    @DisplayName("RedisQuotaStore")
    class RedisStoreTest {

        private MockRedisExecutor mockRedis;
        private RedisQuotaStore store;

        @BeforeEach
        void setUp() {
            mockRedis = new MockRedisExecutor();
            store = new RedisQuotaStore("tenant-1", mockRedis);
        }

        @Test
        @DisplayName("incrementAndGetDailyRequests 调用 Redis")
        void incrementRequests() {
            store.incrementAndGetDailyRequests();
            assertTrue(mockRedis.evalCount > 0 || mockRedis.incrCount > 0);
        }

        @Test
        @DisplayName("decrementDailyRequests 调用 Redis DECR")
        void decrementRequests() {
            store.incrementAndGetDailyRequests();
            store.incrementAndGetDailyRequests();
            store.decrementDailyRequests();
            assertTrue(mockRedis.decrCount > 0);
        }

        @Test
        @DisplayName("addAndGetDailyTokens 调用 Redis INCRBY")
        void addTokens() {
            long result = store.addAndGetDailyTokens(100);
            assertTrue(mockRedis.evalCount > 0);
        }

        @Test
        @DisplayName("getDailyRequests 从 Redis 读取")
        void getRequests() {
            mockRedis.data.put("quota:tenant-1:requests:" + java.time.LocalDate.now(), "42");
            assertEquals(42, store.getDailyRequests());
        }

        @Test
        @DisplayName("getDailyTokens 从 Redis 读取")
        void getTokens() {
            mockRedis.data.put("quota:tenant-1:tokens:" + java.time.LocalDate.now(), "12345");
            assertEquals(12345, store.getDailyTokens());
        }

        @Test
        @DisplayName("getActiveAgents 从 Redis 读取")
        void getAgents() {
            mockRedis.data.put("quota:tenant-1:agents", "3");
            assertEquals(3, store.getActiveAgents());
        }

        @Test
        @DisplayName("getStorageUsage 从 Redis 读取")
        void getStorage() {
            mockRedis.data.put("quota:tenant-1:storage", "999999");
            assertEquals(999999, store.getStorageUsage());
        }

        @Test
        @DisplayName("checkAndResetDaily 返回 false（Redis 按日期 key 自动隔离）")
        void checkAndResetDaily() {
            assertFalse(store.checkAndResetDaily());
        }

        @Test
        @DisplayName("activeAgents 增减调用 Redis")
        void activeAgents() {
            store.incrementActiveAgents();
            assertTrue(mockRedis.incrCount > 0);
            mockRedis.incrCount = 0;
            store.decrementActiveAgents();
            assertTrue(mockRedis.decrCount > 0);
        }

        @Test
        @DisplayName("saveUsage / loadUsage 为 no-op")
        void saveLoadNoop() {
            assertDoesNotThrow(() -> store.saveUsage());
            assertDoesNotThrow(() -> store.loadUsage());
        }

        @Test
        @DisplayName("不存在的 key 返回 0")
        void missingKeyReturnsZero() {
            assertEquals(0, store.getDailyRequests());
            assertEquals(0, store.getDailyTokens());
            assertEquals(0, store.getActiveAgents());
            assertEquals(0, store.getStorageUsage());
        }
    }

    // ========================================================================
    // QuotaStoreFactory
    // ========================================================================

    @Nested
    @DisplayName("QuotaStoreFactory")
    class FactoryTest {

        @Test
        @DisplayName("storeType=local → LocalQuotaStore")
        void createLocal() {
            QuotaStore store = QuotaStoreFactory.create("t1", "local", null);
            assertInstanceOf(LocalQuotaStore.class, store);
        }

        @Test
        @DisplayName("storeType=redis + executor → RedisQuotaStore")
        void createRedis() {
            MockRedisExecutor mock = new MockRedisExecutor();
            QuotaStore store = QuotaStoreFactory.create("t1", "redis", mock);
            assertInstanceOf(RedisQuotaStore.class, store);
        }

        @Test
        @DisplayName("storeType=redis 但 executor=null → fallback to Local")
        void createRedisNoExecutorFallback() {
            QuotaStore store = QuotaStoreFactory.create("t1", "redis", null);
            assertInstanceOf(LocalQuotaStore.class, store);
        }

        @Test
        @DisplayName("storeType=null → Local")
        void createNullType() {
            QuotaStore store = QuotaStoreFactory.create("t1", null, null);
            assertInstanceOf(LocalQuotaStore.class, store);
        }

        @Test
        @DisplayName("createLocal() 工厂方法")
        void createLocalFactory() {
            QuotaStore store = QuotaStoreFactory.createLocal();
            assertInstanceOf(LocalQuotaStore.class, store);
        }
    }

    // ========================================================================
    // TenantQuotaManager 集成（验证 store 被正确使用）
    // ========================================================================

    @Nested
    @DisplayName("TenantQuotaManager + QuotaStore 集成")
    class IntegrationTest {

        @Test
        @DisplayName("默认构造使用 LocalQuotaStore")
        void defaultUsesLocal() throws Exception {
            java.nio.file.Path tmpDir = java.nio.file.Files.createTempDirectory("quota-test");
            TenantQuotaManager manager = new TenantQuotaManager(tmpDir, TenantQuota.defaults());
            manager.checkDailyRequestQuota();
            assertEquals(1, manager.getUsage().getDailyRequests());
        }

        @Test
        @DisplayName("注入 LocalQuotaStore 工作")
        void injectLocalStore() throws Exception {
            java.nio.file.Path tmpDir = java.nio.file.Files.createTempDirectory("quota-test");
            LocalQuotaStore store = new LocalQuotaStore();
            TenantQuotaManager manager = new TenantQuotaManager(tmpDir, TenantQuota.defaults(), store);
            manager.checkDailyRequestQuota();
            manager.checkDailyRequestQuota();
            assertEquals(2, manager.getUsage().getDailyRequests());
        }

        @Test
        @DisplayName("配额超限时抛 QuotaExceededException")
        void quotaExceeded() throws Exception {
            java.nio.file.Path tmpDir = java.nio.file.Files.createTempDirectory("quota-test");
            TenantQuota quota = TenantQuota.defaults();
            quota.setMaxDailyRequests(2);
            TenantQuotaManager manager = new TenantQuotaManager(tmpDir, quota);

            manager.checkDailyRequestQuota(); // 1
            manager.checkDailyRequestQuota(); // 2
            assertThrows(QuotaExceededException.class, manager::checkDailyRequestQuota); // 3 → exceed
        }

        @Test
        @DisplayName("token 配额超限时抛异常并回滚")
        void tokenQuotaExceeded() throws Exception {
            java.nio.file.Path tmpDir = java.nio.file.Files.createTempDirectory("quota-test");
            TenantQuota quota = TenantQuota.defaults();
            quota.setMaxDailyTokens(1000);
            TenantQuotaManager manager = new TenantQuotaManager(tmpDir, quota);

            manager.checkTokenQuota(600); // ok
            assertThrows(QuotaExceededException.class, () -> manager.checkTokenQuota(600)); // 1200 > 1000
            // 回滚后应为 600
            assertEquals(600, manager.getUsage().getDailyTokens());
        }
    }

    // ========================================================================
    // Mock Redis 执行器
    // ========================================================================

    private static class MockRedisExecutor implements RedisQuotaStore.RedisCommandExecutor {
        final Map<String, String> data = new HashMap<>();
        int incrCount = 0;
        int decrCount = 0;
        int evalCount = 0;

        @Override
        public Long eval(String script, String[] keys, String[] args) {
            evalCount++;
            // 简化模拟：INCRBY 逻辑
            String key = keys[0];
            long delta = args.length > 0 ? Long.parseLong(args[0]) : 1;
            long current = Long.parseLong(data.getOrDefault(key, "0"));
            current += delta;
            data.put(key, String.valueOf(current));
            return current;
        }

        @Override
        public Long incr(String key) {
            incrCount++;
            long current = Long.parseLong(data.getOrDefault(key, "0")) + 1;
            data.put(key, String.valueOf(current));
            return current;
        }

        @Override
        public Long decr(String key) {
            decrCount++;
            long current = Long.parseLong(data.getOrDefault(key, "0")) - 1;
            data.put(key, String.valueOf(current));
            return current;
        }

        @Override
        public String get(String key) {
            return data.get(key);
        }

        @Override
        public void expire(String key, long seconds) {
            // no-op for mock
        }

        @Override
        public void del(String key) {
            data.remove(key);
        }
    }
}
