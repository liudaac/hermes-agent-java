package com.nousresearch.hermes.config.repository;

import com.nousresearch.hermes.config.ModelRoute;
import com.nousresearch.hermes.platform.ProviderCatalog;
import com.nousresearch.hermes.tenant.quota.TenantQuota;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * C1+C2+C3: ConfigRepository + ConfigCache tests.
 * Uses LocalConfigRepository (file-based) since no MySQL in test env.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ConfigRepositoryTest {

    @TempDir
    Path tempDir;

    private ConfigRepository repo;
    private ConfigCache cache;

    @BeforeEach
    void setUp() {
        // Create tenants dir structure
        repo = new LocalConfigRepository(tempDir);
        cache = new ConfigCache(repo, 100); // 100ms TTL for fast testing
    }

    // ============ C1: LocalConfigRepository ============

    @Test
    @Order(1)
    @DisplayName("loadModelConfig returns defaults for new tenant")
    void loadModelConfig_defaults() {
        Map<String, Object> config = repo.loadModelConfig("new-tenant");
        assertEquals("doubao", config.get("provider"));
        assertEquals("deepseek-v3-250324", config.get("model"));
        assertEquals("hybrid", config.get("key_source"));
    }

    @Test
    @Order(2)
    @DisplayName("saveModelConfig + loadModelConfig round-trip")
    void saveLoadModelConfig() {
        Map<String, Object> config = Map.of(
            "provider", "openai",
            "model", "gpt-4o",
            "temperature", 0.5,
            "max_tokens", 8192,
            "key_source", "tenant"
        );
        repo.saveModelConfig("t1", config);

        // Reload from repo
        Map<String, Object> loaded = repo.loadModelConfig("t1");
        assertEquals("openai", loaded.get("provider"));
        assertEquals("gpt-4o", loaded.get("model"));
        assertEquals("tenant", loaded.get("key_source"));
    }

    @Test
    @Order(3)
    @DisplayName("saveApiKey + loadApiKeys round-trip")
    void saveLoadApiKey() {
        repo.saveApiKey("t1", "openai", "sk-xxx");
        repo.saveApiKey("t1", "anthropic", "sk-ant-yyy");

        Map<String, String> keys = repo.loadApiKeys("t1");
        assertEquals("sk-xxx", keys.get("openai"));
        assertEquals("sk-ant-yyy", keys.get("anthropic"));
    }

    @Test
    @Order(4)
    @DisplayName("removeApiKey removes specific provider")
    void removeApiKey() {
        repo.saveApiKey("t1", "openai", "sk-xxx");
        repo.removeApiKey("t1", "openai");

        Map<String, String> keys = repo.loadApiKeys("t1");
        assertFalse(keys.containsKey("openai"));
    }

    @Test
    @Order(5)
    @DisplayName("saveModelRoute + loadModelRoutes round-trip")
    void saveLoadModelRoute() {
        repo.saveModelRoute("t1", new ModelRoute("fast", "gpt-4o-mini", "openai", null));
        repo.saveModelRoute("t1", new ModelRoute("smart", "claude-3.5-sonnet", "anthropic", null));

        List<ModelRoute> routes = repo.loadModelRoutes("t1");
        // 2 saved (upsert over classpath "fast"/"smart") + 1 "cheap" from classpath = 3
        assertEquals(3, routes.size());
    }

    @Test
    @Order(6)
    @DisplayName("saveModelRoute upserts existing alias")
    void upsertModelRoute() {
        repo.saveModelRoute("t1", new ModelRoute("fast", "gpt-4o-mini", "openai", null));
        repo.saveModelRoute("t1", new ModelRoute("fast", "gpt-4.1-mini", "openai", null));

        List<ModelRoute> routes = repo.loadModelRoutes("t1");
        assertEquals(3, routes.size());
        var fast = routes.stream().filter(r -> "fast".equals(r.getAlias())).findFirst().orElse(null);
        assertNotNull(fast);
        assertEquals("gpt-4.1-mini", fast.getModel());
    }

    @Test
    @Order(7)
    @DisplayName("removeModelRoute removes by alias")
    void removeModelRoute() {
        repo.saveModelRoute("t1", new ModelRoute("fast", "gpt-4o-mini", "openai", null));
        repo.saveModelRoute("t1", new ModelRoute("smart", "claude-3.5-sonnet", "anthropic", null));
        repo.removeModelRoute("t1", "fast");

        List<ModelRoute> routes = repo.loadModelRoutes("t1");
        // 3 classpath defaults - 1 removed = 2
        assertEquals(2, routes.size());
        var fast = routes.stream().filter(r -> "fast".equals(r.getAlias())).findFirst().orElse(null);
        assertNull(fast);
    }

    @Test
    @Order(8)
    @DisplayName("loadQuota returns defaults")
    void loadQuota_defaults() {
        TenantQuota quota = repo.loadQuota("t1");
        assertNotNull(quota);
        assertEquals(10000, quota.getMaxDailyRequests());
    }

    @Test
    @Order(9)
    @DisplayName("loadProviderCatalog returns 8 providers")
    void loadProviderCatalog() {
        ProviderCatalog catalog = repo.loadProviderCatalog();
        assertEquals(8, catalog.listProviderIds().size());
        assertTrue(catalog.isRegistered("openai"));
    }

    @Test
    @Order(10)
    @DisplayName("getConfigVersion returns non-zero after save")
    void getConfigVersion() {
        repo.saveModelConfig("t1", Map.of("provider", "openai", "model", "gpt-4o"));
        long v = repo.getConfigVersion("t1");
        assertTrue(v > 0);
    }

    // ============ C3: ConfigCache ============

    @Test
    @Order(11)
    @DisplayName("ConfigCache caches within TTL")
    void cache_hit() {
        repo.saveApiKey("t1", "openai", "sk-first");

        // First call -> loads from repo
        Map<String, String> keys1 = cache.loadApiKeys("t1");
        assertEquals("sk-first", keys1.get("openai"));

        // Change underlying repo directly (bypass cache)
        repo.saveApiKey("t1", "openai", "sk-changed");

        // Within TTL -> cache hit, should still return old value
        Map<String, String> keys2 = cache.loadApiKeys("t1");
        assertEquals("sk-first", keys2.get("openai"));
    }

    @Test
    @Order(12)
    @DisplayName("ConfigCache refreshes after TTL expiry")
    void cache_expiry() throws InterruptedException {
        cache = new ConfigCache(repo, 50); // 50ms TTL
        repo.saveApiKey("t1", "openai", "sk-first");

        // First call
        Map<String, String> keys1 = cache.loadApiKeys("t1");
        assertEquals("sk-first", keys1.get("openai"));

        // Wait for TTL to expire
        Thread.sleep(60);

        // Change underlying
        repo.saveApiKey("t1", "openai", "sk-refreshed");

        // Should reload from repo
        Map<String, String> keys2 = cache.loadApiKeys("t1");
        assertEquals("sk-refreshed", keys2.get("openai"));
    }

    @Test
    @Order(13)
    @DisplayName("ConfigCache invalidate forces immediate refresh")
    void cache_invalidate() {
        repo.saveApiKey("t1", "openai", "sk-first");

        // Load into cache
        cache.loadApiKeys("t1");

        // Change underlying
        repo.saveApiKey("t1", "openai", "sk-changed");

        // Invalidate
        cache.invalidate("t1");

        // Should reload
        Map<String, String> keys = cache.loadApiKeys("t1");
        assertEquals("sk-changed", keys.get("openai"));
    }

    @Test
    @Order(14)
    @DisplayName("ConfigCache invalidateAll clears everything")
    void cache_invalidateAll() {
        repo.saveApiKey("t1", "openai", "sk-1");
        repo.saveApiKey("t2", "openai", "sk-2");

        cache.loadApiKeys("t1");
        cache.loadApiKeys("t2");

        cache.invalidateAll();

        ConfigCache.CacheStats stats = cache.getStats();
        assertEquals(0, stats.apiKeyEntries());
    }

    @Test
    @Order(15)
    @DisplayName("saveModelConfig through cache invalidates cache")
    void cache_saveInvalidates() {
        repo.saveModelConfig("t1", Map.of("provider", "openai", "model", "gpt-4o"));

        // Load into cache
        Map<String, Object> c1 = cache.loadModelConfig("t1");
        assertEquals("gpt-4o", c1.get("model"));

        // Save through cache -> should invalidate
        cache.saveModelConfig("t1", Map.of("provider", "anthropic", "model", "claude-3-opus"));

        // Should get fresh data
        Map<String, Object> c2 = cache.loadModelConfig("t1");
        assertEquals("claude-3-opus", c2.get("model"));
    }

    @Test
    @Order(16)
    @DisplayName("getStats returns cache statistics")
    void cache_stats() {
        cache.loadApiKeys("t1");
        cache.loadModelConfig("t1");

        ConfigCache.CacheStats stats = cache.getStats();
        assertTrue(stats.apiKeyEntries() >= 1);
        assertTrue(stats.modelConfigEntries() >= 1);
        assertEquals(100, stats.ttlMs());
    }
}
