package com.nousresearch.hermes.tenant.core;

import com.nousresearch.hermes.config.HermesConfig;
import com.nousresearch.hermes.config.ModelRoute;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * B1: Tenant-level model configuration resolution tests.
 *
 * Verifies that TenantConfig.buildModelConfig() correctly resolves
 * model/provider/apiKey/baseUrl from tenant-scoped config + secrets.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TenantConfigModelConfigTest {

    @TempDir
    Path tempDir;

    private TenantConfig config;

    @BeforeEach
    void setUp() {
        config = new TenantConfig(tempDir.resolve("config"), Map.of());
    }

    @Test
    @Order(1)
    @DisplayName("buildModelConfig returns defaults when nothing configured")
    void buildModelConfig_defaults() {
        HermesConfig.ModelConfig mc = config.buildModelConfig();
        assertNotNull(mc);
        assertEquals("doubao", mc.getProvider());
        assertEquals("deepseek-v3-250324", mc.getName());
        // default base_url for openrouter
        assertEquals("https://ark.cn-beijing.volces.com/api/v3", mc.getBaseUrl());
    }

    @Test
    @Order(2)
    @DisplayName("buildModelConfig reads tenant model.* config")
    void buildModelConfig_tenantConfig() {
        config.set("model.provider", "openai");
        config.set("model.model", "gpt-4o");
        config.set("model.base_url", "https://custom.openai-proxy.com/v1");

        HermesConfig.ModelConfig mc = config.buildModelConfig();
        assertEquals("openai", mc.getProvider());
        assertEquals("gpt-4o", mc.getName());
        assertEquals("https://custom.openai-proxy.com/v1", mc.getBaseUrl());
    }

    @Test
    @Order(3)
    @DisplayName("resolveApiKey reads {PROVIDER}_API_KEY from secrets")
    void resolveApiKey_providerSpecific() {
        config.setSecret("OPENAI_API_KEY", "sk-openai-xxx");

        String key = config.resolveApiKey("openai");
        assertEquals("sk-openai-xxx", key);
    }

    @Test
    @Order(4)
    @DisplayName("resolveApiKey falls back to generic API_KEY")
    void resolveApiKey_genericFallback() {
        config.setSecret("API_KEY", "sk-generic-xxx");

        String key = config.resolveApiKey("deepseek");
        assertEquals("sk-generic-xxx", key);
    }

    @Test
    @Order(5)
    @DisplayName("resolveApiKey provider-specific takes priority over generic")
    void resolveApiKey_priority() {
        config.setSecret("API_KEY", "sk-generic-xxx");
        config.setSecret("ANTHROPIC_API_KEY", "sk-ant-xxx");

        String key = config.resolveApiKey("anthropic");
        assertEquals("sk-ant-xxx", key);

        // Other providers still get generic
        key = config.resolveApiKey("openai");
        assertEquals("sk-generic-xxx", key);
    }

    // ============ B2: model_routes tests ============

    // ============ B3: Provider Catalog integration ============

}
