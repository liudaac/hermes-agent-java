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

    // ============ C3: ConfigCache ============

}
