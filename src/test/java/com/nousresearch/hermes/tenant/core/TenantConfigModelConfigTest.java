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

    // ============ B2: model_routes tests ============

    @Test
    @Order(13)
    @DisplayName("getModelRoutes returns empty list when not configured")
    void getModelRoutes_empty() {
        assertTrue(config.getModelRoutes().isEmpty());
    }

    @Test
    @Order(14)
    @DisplayName("getModelRoutes reads tenant model_routes config")
    void getModelRoutes_tenantConfig() {
        config.set("model_routes", List.of(
            Map.of("alias", "fast", "model", "gpt-4o-mini", "provider", "openai"),
            Map.of("alias", "smart", "model", "claude-3.5-sonnet", "provider", "anthropic")
        ));

        List<ModelRoute> routes = config.getModelRoutes();
        assertEquals(2, routes.size());
        assertEquals("fast", routes.get(0).getAlias());
        assertEquals("gpt-4o-mini", routes.get(0).getModel());
        assertEquals("openai", routes.get(0).getProvider());
        assertEquals("smart", routes.get(1).getAlias());
        assertEquals("claude-3.5-sonnet", routes.get(1).getModel());
    }

    @Test
    @Order(15)
    @DisplayName("resolveModelRoute finds tenant route by alias")
    void resolveModelRoute_tenantMatch() {
        config.set("model_routes", List.of(
            Map.of("alias", "fast", "model", "gpt-4o-mini", "provider", "openai")
        ));

        ModelRoute route = config.resolveModelRoute("fast");
        assertNotNull(route);
        assertEquals("gpt-4o-mini", route.getModel());
        assertEquals("openai", route.getProvider());
    }

    @Test
    @Order(16)
    @DisplayName("resolveModelRoute is case-insensitive")
    void resolveModelRoute_caseInsensitive() {
        config.set("model_routes", List.of(
            Map.of("alias", "Smart", "model", "claude-3.5-sonnet", "provider", "anthropic")
        ));

        assertNotNull(config.resolveModelRoute("smart"));
        assertNotNull(config.resolveModelRoute("SMART"));
        assertNotNull(config.resolveModelRoute("Smart"));
    }

    @Test
    @Order(17)
    @DisplayName("resolveModelRoute falls back to platform routes")
    void resolveModelRoute_platformFallback() {
        List<ModelRoute> platformRoutes = List.of(
            new ModelRoute("fast", "gpt-4o-mini", "openai", null),
            new ModelRoute("smart", "claude-3.5-sonnet", "anthropic", null)
        );

        // Tenant has no model_routes -> should find in platform
        ModelRoute route = config.resolveModelRoute("fast", platformRoutes);
        assertNotNull(route);
        assertEquals("gpt-4o-mini", route.getModel());
    }

    @Test
    @Order(18)
    @DisplayName("resolveModelRoute tenant override takes priority over platform")
    void resolveModelRoute_tenantOverride() {
        // Platform defines "fast" as gpt-4o-mini
        List<ModelRoute> platformRoutes = List.of(
            new ModelRoute("fast", "gpt-4o-mini", "openai", null)
        );

        // Tenant overrides "fast" as claude-3-5-haiku
        config.set("model_routes", List.of(
            Map.of("alias", "fast", "model", "claude-3-5-haiku", "provider", "anthropic")
        ));

        ModelRoute route = config.resolveModelRoute("fast", platformRoutes);
        assertNotNull(route);
        assertEquals("claude-3-5-haiku", route.getModel());  // tenant wins
        assertEquals("anthropic", route.getProvider());
    }

    @Test
    @Order(19)
    @DisplayName("resolveModelRoute returns null when no match")
    void resolveModelRoute_noMatch() {
        config.set("model_routes", List.of(
            Map.of("alias", "fast", "model", "gpt-4o-mini", "provider", "openai")
        ));

        assertNull(config.resolveModelRoute("nonexistent"));
        assertNull(config.resolveModelRoute("nonexistent", List.of()));
    }

    @Test
    @Order(20)
    @DisplayName("resolveModelConfig by alias builds full ModelConfig with API key")
    void resolveModelConfig_byAlias() {
        config.set("model_routes", List.of(
            Map.of("alias", "smart", "model", "claude-3.5-sonnet", "provider", "anthropic")
        ));
        config.setSecret("ANTHROPIC_API_KEY", "sk-ant-xxx");

        HermesConfig.ModelConfig mc = config.resolveModelConfig("smart");
        assertEquals("anthropic", mc.getProvider());
        assertEquals("claude-3.5-sonnet", mc.getName());
        assertEquals("sk-ant-xxx", mc.getApiKey());
        assertEquals("https://api.anthropic.com/v1", mc.getBaseUrl());
    }

    @Test
    @Order(21)
    @DisplayName("resolveModelConfig falls back to platform routes then tenant default")
    void resolveModelConfig_fallbackChain() {
        List<ModelRoute> platformRoutes = List.of(
            new ModelRoute("cheap", "deepseek-chat", "deepseek", null)
        );

        // "cheap" is only in platform routes
        HermesConfig.ModelConfig mc = config.resolveModelConfig("cheap", platformRoutes);
        assertEquals("deepseek", mc.getProvider());
        assertEquals("deepseek-chat", mc.getName());
        assertEquals("https://api.deepseek.com/v1", mc.getBaseUrl());

        // Unknown alias -> falls back to tenant default (openrouter)
        mc = config.resolveModelConfig("unknown", platformRoutes);
        assertEquals("openrouter", mc.getProvider());
        assertEquals("anthropic/claude-3.5-sonnet", mc.getName());
    }

    @Test
    @Order(22)
    @DisplayName("resolveModelConfig with null alias returns tenant default")
    void resolveModelConfig_nullAlias() {
        HermesConfig.ModelConfig mc = config.resolveModelConfig(null);
        assertEquals("openrouter", mc.getProvider());
        assertEquals("anthropic/claude-3.5-sonnet", mc.getName());
    }

    @Test
    @Order(23)
    @DisplayName("getAllModelAliases merges tenant + platform without duplicates")
    void getAllModelAliases_merged() {
        config.set("model_routes", List.of(
            Map.of("alias", "fast", "model", "gpt-4o-mini", "provider", "openai"),
            Map.of("alias", "local", "model", "qwen2.5:72b", "provider", "ollama")
        ));
        List<ModelRoute> platformRoutes = List.of(
            new ModelRoute("fast", "gpt-4o-mini", "openai", null),  // duplicate
            new ModelRoute("smart", "claude-3.5-sonnet", "anthropic", null),  // unique
            new ModelRoute("cheap", "deepseek-chat", "deepseek", null)  // unique
        );

        List<String> aliases = config.getAllModelAliases(platformRoutes);
        assertEquals(4, aliases.size());  // fast, local, smart, cheap (no dup)
        assertTrue(aliases.contains("fast"));
        assertTrue(aliases.contains("local"));
        assertTrue(aliases.contains("smart"));
        assertTrue(aliases.contains("cheap"));
    }

    @Test
    @Order(24)
    @DisplayName("resolveModelConfig uses route base_url when set")
    void resolveModelConfig_routeBaseUrl() {
        config.set("model_routes", List.of(
            Map.of("alias", "proxy", "model", "gpt-4o", "provider", "openai",
                   "base-url", "https://my-proxy.example.com/v1")
        ));
        config.setSecret("OPENAI_API_KEY", "sk-xxx");

        HermesConfig.ModelConfig mc = config.resolveModelConfig("proxy");
        assertEquals("https://my-proxy.example.com/v1", mc.getBaseUrl());
    }
}
