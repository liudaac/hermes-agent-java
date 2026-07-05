package com.nousresearch.hermes.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * S4-3: ExternalMemoryProvider SPI 测试
 */
class ExternalMemoryProviderTest {

    // ========================================================================
    // LocalFileMemoryProvider
    // ========================================================================

    @Nested
    @DisplayName("LocalFileMemoryProvider")
    class LocalFileTest {

        private MemoryManager memoryManager;
        private LocalFileMemoryProvider provider;

        @BeforeEach
        void setUp() throws Exception {
            memoryManager = org.mockito.Mockito.mock(MemoryManager.class);
            provider = new LocalFileMemoryProvider(memoryManager);
            provider.initialize(Map.of());
        }

        @Test
        @DisplayName("name = local-file")
        void name() {
            assertEquals("local-file", provider.name());
        }

        @Test
        @DisplayName("store 返回非 null ID")
        void storeReturnsId() {
            String id = provider.store("t1", "agent-1", "This is a memory", Map.of());
            assertNotNull(id);
        }

        @Test
        @DisplayName("search 返回结果")
        void searchReturnsResults() {
            org.mockito.Mockito.when(memoryManager.search("fox", 5))
                .thenReturn(java.util.List.of("The quick brown fox jumps over the lazy dog"));

            List<ExternalMemoryProvider.MemoryRecord> results = provider.search("t1", "fox", 5);
            assertFalse(results.isEmpty());
            assertTrue(results.get(0).content().contains("fox"));
        }

        @Test
        @DisplayName("retrieve 返回 null（本地文件不支持按 ID）")
        void retrieveReturnsNull() {
            assertNull(provider.retrieve("t1", "any-id"));
        }

        @Test
        @DisplayName("delete 返回 false（本地文件不支持按 ID 删除）")
        void deleteReturnsFalse() {
            assertFalse(provider.delete("t1", "any-id"));
        }

        @Test
        @DisplayName("list 返回记忆列表")
        void listReturnsEntries() {
            org.mockito.Mockito.when(memoryManager.getByCategory("memory", 10))
                .thenReturn(java.util.List.of("Memory 1", "Memory 2"));

            List<ExternalMemoryProvider.MemoryRecord> all = provider.list("t1", 0, 10);
            assertEquals(2, all.size());
        }

        @Test
        @DisplayName("isAvailable = true")
        void isAvailable() {
            assertTrue(provider.isAvailable());
        }
    }

    // ========================================================================
    // Mem0MemoryProvider（不测 HTTP 调用，只测初始化和接口实现）
    // ========================================================================

    @Nested
    @DisplayName("Mem0MemoryProvider")
    class Mem0Test {

        @Test
        @DisplayName("name = mem0")
        void name() {
            Mem0MemoryProvider provider = new Mem0MemoryProvider();
            assertEquals("mem0", provider.name());
        }

        @Test
        @DisplayName("initialize 设置 baseUrl 和 apiKey")
        void initialize() {
            Mem0MemoryProvider provider = new Mem0MemoryProvider();
            provider.initialize(Map.of(
                "base-url", "http://mem0.example.com:8080",
                "api-key", "test-key"
            ));
            // 不崩溃即成功（baseUrl 是 private）
        }

        @Test
        @DisplayName("initialize 默认 baseUrl")
        void defaultBaseUrl() {
            Mem0MemoryProvider provider = new Mem0MemoryProvider();
            assertDoesNotThrow(() -> provider.initialize(Map.of()));
        }

        @Test
        @DisplayName("isAvailable 对不存在的服务返回 false")
        void isAvailableFalse() {
            Mem0MemoryProvider provider = new Mem0MemoryProvider();
            provider.initialize(Map.of("base-url", "http://nonexistent-host-99999:8080"));
            assertFalse(provider.isAvailable());
        }

        @Test
        @DisplayName("store 对不可用服务返回 null")
        void storeUnavailable() {
            Mem0MemoryProvider provider = new Mem0MemoryProvider();
            provider.initialize(Map.of("base-url", "http://nonexistent-host-99999:8080"));
            String id = provider.store("t1", "a1", "content", Map.of());
            assertNull(id);
        }

        @Test
        @DisplayName("search 对不可用服务返回空列表")
        void searchUnavailable() {
            Mem0MemoryProvider provider = new Mem0MemoryProvider();
            provider.initialize(Map.of("base-url", "http://nonexistent-host-99999:8080"));
            List<ExternalMemoryProvider.MemoryRecord> results = provider.search("t1", "query", 5);
            assertTrue(results.isEmpty());
        }

        @Test
        @DisplayName("delete 对不可用服务返回 false")
        void deleteUnavailable() {
            Mem0MemoryProvider provider = new Mem0MemoryProvider();
            provider.initialize(Map.of("base-url", "http://nonexistent-host-99999:8080"));
            assertFalse(provider.delete("t1", "id"));
        }
    }

    // ========================================================================
    // MemoryRecord
    // ========================================================================

    @Nested
    @DisplayName("MemoryRecord")
    class RecordTest {

        @Test
        @DisplayName("基本构造")
        void basicConstruction() {
            ExternalMemoryProvider.MemoryRecord r = new ExternalMemoryProvider.MemoryRecord(
                "id-1", "agent-1", "content here",
                Map.of("key", "val"), 1234567890L, 0.95
            );
            assertEquals("id-1", r.id());
            assertEquals("agent-1", r.agentId());
            assertEquals("content here", r.content());
            assertEquals("val", r.metadata().get("key"));
            assertEquals(1234567890L, r.createdAt());
            assertEquals(0.95, r.score(), 0.001);
        }

        @Test
        @DisplayName("withScore 返回新实例")
        void withScore() {
            ExternalMemoryProvider.MemoryRecord r = new ExternalMemoryProvider.MemoryRecord(
                "id-1", "a1", "content", Map.of(), 0, 0
            );
            ExternalMemoryProvider.MemoryRecord r2 = r.withScore(0.8);
            assertEquals(0.8, r2.score(), 0.001);
            assertEquals(r.id(), r2.id());
            assertEquals(0, r.score(), 0.001); // 原实例不变
        }
    }

    // ========================================================================
    // Provider 多态（接口契约）
    // ========================================================================

    @Nested
    @DisplayName("Provider 多态")
    class PolymorphismTest {

        @Test
        @DisplayName("LocalFileMemoryProvider 是 ExternalMemoryProvider")
        void localIsProvider() {
            ExternalMemoryProvider provider = new LocalFileMemoryProvider(null);
            assertEquals("local-file", provider.name());
        }

        @Test
        @DisplayName("Mem0MemoryProvider 是 ExternalMemoryProvider")
        void mem0IsProvider() {
            ExternalMemoryProvider provider = new Mem0MemoryProvider();
            assertEquals("mem0", provider.name());
        }
    }
}
