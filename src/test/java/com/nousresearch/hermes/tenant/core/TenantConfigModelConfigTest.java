package com.nousresearch.hermes.tenant.core;

import com.nousresearch.hermes.config.HermesConfig;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
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
        assertEquals("openrouter", mc.getProvider());
        assertEquals("anthropic/claude-3.5-sonnet", mc.getName());
        // default base_url for openrouter
        assertEquals("https://openrouter.ai/api/v1", mc.getBaseUrl());
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

    @Test
    @Order(6)
    @DisplayName("resolveApiKey returns null when nothing configured")
    void resolveApiKey_null() {
        String key = config.resolveApiKey("openai");
        assertNull(key);
    }

    @Test
    @Order(7)
    @DisplayName("resolveApiKey reads model.api_key only for default provider")
    void resolveApiKey_configYamlOnlyForDefaultProvider() {
        config.set("model.provider", "openai");
        config.set("model.api_key", "sk-from-config-yaml");

        // openai is the default provider -> should find the key
        String key = config.resolveApiKey("openai");
        assertEquals("sk-from-config-yaml", key);

        // anthropic is NOT the default -> should NOT find it from model.api_key
        key = config.resolveApiKey("anthropic");
        assertNull(key);
    }

    @Test
    @Order(8)
    @DisplayName("resolveBaseUrl falls back to provider default")
    void resolveBaseUrl_providerDefault() {
        config.set("model.provider", "deepseek");

        String url = config.resolveBaseUrl("deepseek");
        assertEquals("https://api.deepseek.com/v1", url);
    }

    @Test
    @Order(9)
    @DisplayName("resolveBaseUrl uses configured value when set")
    void resolveBaseUrl_configuredValue() {
        config.set("model.base_url", "https://my-proxy.example.com/v1");

        String url = config.resolveBaseUrl("openai");
        assertEquals("https://my-proxy.example.com/v1", url);
    }

    @Test
    @Order(10)
    @DisplayName("buildModelConfig with global fallback uses global values")
    void buildModelConfig_globalFallback() {
        HermesConfig global = new HermesConfig();
        global.set("model.provider", "anthropic");
        global.set("model.model", "claude-3-opus");
        global.set("model.api_key", "sk-global-xxx");

        // Tenant has no model config -> falls back to global
        HermesConfig.ModelConfig mc = config.buildModelConfig(global);
        assertEquals("openrouter", mc.getProvider()); // tenant default
        assertEquals("anthropic/claude-3.5-sonnet", mc.getName()); // tenant default
        // No tenant API key for openrouter -> falls back to global api key
        assertEquals("sk-global-xxx", mc.getApiKey());
    }

    @Test
    @Order(11)
    @DisplayName("buildModelConfig with global fallback: tenant values override global")
    void buildModelConfig_tenantOverridesGlobal() {
        HermesConfig global = new HermesConfig();
        global.set("model.provider", "anthropic");
        global.set("model.model", "claude-3-opus");
        global.set("model.api_key", "sk-global-xxx");

        // Tenant config overrides
        config.set("model.provider", "openai");
        config.set("model.model", "gpt-4o");
        config.setSecret("OPENAI_API_KEY", "sk-tenant-openai-xxx");

        HermesConfig.ModelConfig mc = config.buildModelConfig(global);
        assertEquals("openai", mc.getProvider());
        assertEquals("gpt-4o", mc.getName());
        assertEquals("sk-tenant-openai-xxx", mc.getApiKey());
    }

    @Test
    @Order(12)
    @DisplayName("All supported providers have default base URLs")
    void resolveBaseUrl_allProviders() {
        assertNotNull(config.resolveBaseUrl("openai"));
        assertNotNull(config.resolveBaseUrl("anthropic"));
        assertNotNull(config.resolveBaseUrl("openrouter"));
        assertNotNull(config.resolveBaseUrl("deepseek"));
        assertNotNull(config.resolveBaseUrl("doubao"));
        assertNotNull(config.resolveBaseUrl("moonshot"));
        assertNotNull(config.resolveBaseUrl("minimax"));
        assertNotNull(config.resolveBaseUrl("ollama"));
        // Unknown provider falls back to openrouter
        assertNotNull(config.resolveBaseUrl("unknown-provider"));
    }
}
