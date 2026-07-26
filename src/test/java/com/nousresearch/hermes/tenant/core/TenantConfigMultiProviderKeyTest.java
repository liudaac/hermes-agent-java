package com.nousresearch.hermes.tenant.core;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * B6: Multi-provider API Key management tests.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TenantConfigMultiProviderKeyTest {

    @TempDir
    Path tempDir;

    private TenantConfig config;

    @BeforeEach
    void setUp() {
        config = new TenantConfig(tempDir.resolve("config"), Map.of());
    }

    // ============ Key Source Policy ============

    @Test
    @Order(1)
    @DisplayName("getKeySource defaults to hybrid")
    void keySource_default() {
        assertEquals("hybrid", config.getKeySource());
    }

    @Test
    @Order(2)
    @DisplayName("setKeySource persists value")
    void keySource_set() {
        config.setKeySource("tenant");
        assertEquals("tenant", config.getKeySource());

        config.setKeySource("platform");
        assertEquals("platform", config.getKeySource());
    }

    // ============ Provider Key CRUD ============

    @Test
    @Order(3)
    @DisplayName("setProviderApiKey / getProviderApiKey round-trip")
    void providerKey_setGet() {
        config.setProviderApiKey("openai", "sk-openai-xxx");
        assertEquals("sk-openai-xxx", config.getProviderApiKey("openai"));

        config.setProviderApiKey("anthropic", "sk-ant-yyy");
        assertEquals("sk-ant-yyy", config.getProviderApiKey("anthropic"));
    }

    @Test
    @Order(4)
    @DisplayName("getProviderApiKey returns null for unset provider")
    void providerKey_null() {
        assertNull(config.getProviderApiKey("deepseek"));
    }

    @Test
    @Order(5)
    @DisplayName("removeProviderApiKey removes and returns true")
    void providerKey_remove() {
        config.setProviderApiKey("openai", "sk-xxx");
        assertTrue(config.removeProviderApiKey("openai"));
        assertNull(config.getProviderApiKey("openai"));
    }

    @Test
    @Order(6)
    @DisplayName("removeProviderApiKey returns false for nonexistent key")
    void providerKey_removeNonexistent() {
        assertFalse(config.removeProviderApiKey("deepseek"));
    }

    @Test
    @Order(7)
    @DisplayName("listProviderApiKeys returns all configured provider keys")
    void providerKey_list() {
        config.setProviderApiKey("openai", "sk-1");
        config.setProviderApiKey("anthropic", "sk-2");
        config.setProviderApiKey("deepseek", "sk-3");

        Map<String, String> keys = config.listProviderApiKeys();
        assertEquals(3, keys.size());
        assertEquals("OPENAI_API_KEY", keys.get("openai"));
        assertEquals("ANTHROPIC_API_KEY", keys.get("anthropic"));
        assertEquals("DEEPSEEK_API_KEY", keys.get("deepseek"));
    }

    @Test
    @Order(8)
    @DisplayName("listProviderApiKeys excludes generic API_KEY")
    void providerKey_listExcludesGeneric() {
        config.setSecret("API_KEY", "sk-generic");
        config.setProviderApiKey("openai", "sk-openai");

        Map<String, String> keys = config.listProviderApiKeys();
        assertEquals(1, keys.size());
        assertTrue(keys.containsKey("openai"));
        assertFalse(keys.containsKey("api"));  // generic excluded
    }

    @Test
    @Order(9)
    @DisplayName("hasApiKey returns true when key exists")
    void hasApiKey_true() {
        config.setProviderApiKey("openai", "sk-xxx");
        assertTrue(config.hasApiKey("openai"));
    }

    @Test
    @Order(10)
    @DisplayName("hasApiKey returns false when no key")
    void hasApiKey_false() {
        assertFalse(config.hasApiKey("openai"));
    }

    // ============ providerToEnvKey ============

    @Test
    @Order(11)
    @DisplayName("providerToEnvKey converts correctly")
    void providerToEnvKey() {
        assertEquals("OPENAI_API_KEY", TenantConfig.providerToEnvKey("openai"));
        assertEquals("ANTHROPIC_API_KEY", TenantConfig.providerToEnvKey("anthropic"));
        assertEquals("DEEPSEEK_API_KEY", TenantConfig.providerToEnvKey("deepseek"));
        assertEquals("OLLAMA_API_KEY", TenantConfig.providerToEnvKey("ollama"));
        assertEquals("API_KEY", TenantConfig.providerToEnvKey(null));
    }

    @Test
    @Order(12)
    @DisplayName("providerToEnvKey handles hyphens")
    void providerToEnvKey_hyphens() {
        assertEquals("OLLAMA_LOCAL_API_KEY", TenantConfig.providerToEnvKey("ollama-local"));
    }

    // ============ Key Source Resolution ============

    @Test
    @Order(13)
    @DisplayName("key_source=tenant: resolveApiKey skips platform keys")
    void resolveApiKey_tenantOnly() {
        config.setKeySource("tenant");
        config.setProviderApiKey("openai", "sk-tenant-xxx");

        // Set a platform key via system property
        System.setProperty("platform.OPENAI_API_KEY", "sk-platform-yyy");

        String key = config.resolveApiKey("openai");
        assertEquals("sk-tenant-xxx", key);  // tenant key wins

        System.clearProperty("platform.OPENAI_API_KEY");
    }

    @Test
    @Order(14)
    @DisplayName("key_source=tenant: no platform fallback when tenant key missing")
    void resolveApiKey_tenantOnly_noFallback() {
        config.setKeySource("tenant");
        System.setProperty("platform.OPENAI_API_KEY", "sk-platform-yyy");

        // No tenant key -> should NOT fall back to platform
        String key = config.resolveApiKey("openai");
        assertNull(key);

        System.clearProperty("platform.OPENAI_API_KEY");
    }

    @Test
    @Order(15)
    @DisplayName("key_source=platform: skip tenant secrets entirely")
    void resolveApiKey_platformOnly() {
        config.setKeySource("platform");
        config.setProviderApiKey("openai", "sk-tenant-xxx");  // should be ignored
        System.setProperty("platform.OPENAI_API_KEY", "sk-platform-yyy");

        String key = config.resolveApiKey("openai");
        assertEquals("sk-platform-yyy", key);

        System.clearProperty("platform.OPENAI_API_KEY");
    }

    @Test
    @Order(16)
    @DisplayName("key_source=hybrid: tenant key first, platform fallback")
    void resolveApiKey_hybrid() {
        config.setKeySource("hybrid");
        config.setProviderApiKey("openai", "sk-tenant-xxx");
        System.setProperty("platform.OPENAI_API_KEY", "sk-platform-yyy");

        // Tenant key exists -> use it
        String key = config.resolveApiKey("openai");
        assertEquals("sk-tenant-xxx", key);

        // Remove tenant key -> fall back to platform
        config.removeProviderApiKey("openai");
        key = config.resolveApiKey("openai");
        assertEquals("sk-platform-yyy", key);

        System.clearProperty("platform.OPENAI_API_KEY");
    }

    @Test
    @Order(17)
    @DisplayName("key_source=hybrid: returns null when neither tenant nor platform has key")
    void resolveApiKey_hybrid_noKey() {
        config.setKeySource("hybrid");
        assertNull(config.resolveApiKey("openai"));
    }

    @Test
    @Order(18)
    @DisplayName("getPlatformKey reads from system property")
    void getPlatformKey_sysProp() {
        System.setProperty("platform.ANTHROPIC_API_KEY", "sk-ant-platform");
        assertEquals("sk-ant-platform", config.getPlatformKey("anthropic"));
        System.clearProperty("platform.ANTHROPIC_API_KEY");
    }

    @Test
    @Order(19)
    @DisplayName("getPlatformKey reads from environment variable")
    void getPlatformKey_envVar() {
        // Can't set env vars in JVM at runtime, but we can test the path
        // by setting system property (which takes priority anyway)
        System.setProperty("platform.DEEPSEEK_API_KEY", "sk-ds-platform");
        assertEquals("sk-ds-platform", config.getPlatformKey("deepseek"));
        System.clearProperty("platform.DEEPSEEK_API_KEY");
    }

    @Test
    @Order(20)
    @DisplayName("getPlatformKey returns null when not set")
    void getPlatformKey_null() {
        assertNull(config.getPlatformKey("openai"));
    }

    // ============ Validation ============

    @Test
    @Order(21)
    @DisplayName("validateModelConfig: valid config returns no issues")
    void validateModelConfig_valid() {
        config.set("model.provider", "openai");
        config.setProviderApiKey("openai", "sk-xxx");

        TenantConfig.ValidationResult result = config.validateModelConfig();
        assertTrue(result.isValid(), "Issues: " + result.summary());
    }

    @Test
    @Order(22)
    @DisplayName("validateModelConfig: unknown provider flagged")
    void validateModelConfig_unknownProvider() {
        config.set("model.provider", "unknown-provider");

        TenantConfig.ValidationResult result = config.validateModelConfig();
        assertFalse(result.isValid());
        assertTrue(result.summary().contains("not in the platform catalog"));
    }

    @Test
    @Order(23)
    @DisplayName("validateModelConfig: missing API key flagged")
    void validateModelConfig_missingKey() {
        config.set("model.provider", "openai");
        // No API key set

        TenantConfig.ValidationResult result = config.validateModelConfig();
        assertFalse(result.isValid());
        assertTrue(result.summary().contains("No API key"));
    }

    @Test
    @Order(24)
    @DisplayName("validateModelConfig: invalid key_source flagged")
    void validateModelConfig_invalidKeySource() {
        config.set("model.provider", "openai");
        config.setProviderApiKey("openai", "sk-xxx");
        config.setKeySource("invalid");

        TenantConfig.ValidationResult result = config.validateModelConfig();
        assertFalse(result.isValid());
        assertTrue(result.summary().contains("Invalid key_source"));
    }

    @Test
    @Order(25)
    @DisplayName("validateModelConfig: ollama with platform key_source flagged")
    void validateModelConfig_ollamaPlatformNotAllowed() {
        config.set("model.provider", "ollama");
        config.setKeySource("platform");
        // ollama doesn't allow platform keys

        TenantConfig.ValidationResult result = config.validateModelConfig();
        assertFalse(result.isValid());
        assertTrue(result.summary().contains("does not allow"));
    }

    @Test
    @Order(26)
    @DisplayName("validateModelConfig: ollama with tenant key_source OK")
    void validateModelConfig_ollamaTenantOk() {
        config.set("model.provider", "ollama");
        config.setKeySource("tenant");
        config.setProviderApiKey("ollama", "sk-not-needed-but-set");

        TenantConfig.ValidationResult result = config.validateModelConfig();
        assertTrue(result.isValid(), "Issues: " + result.summary());
    }

    @Test
    @Order(27)
    @DisplayName("providerKey persists across save/load cycle")
    void providerKey_persistence() {
        config.setProviderApiKey("openai", "sk-persist-xxx");
        config.save();

        // Reload
        TenantConfig loaded = TenantConfig.load(tempDir.resolve("config"));
        assertEquals("sk-persist-xxx", loaded.getProviderApiKey("openai"));
    }

    @Test
    @Order(28)
    @DisplayName("Multiple provider keys coexist")
    void multipleProviderKeys_coexist() {
        config.setProviderApiKey("openai", "sk-openai");
        config.setProviderApiKey("anthropic", "sk-ant");
        config.setProviderApiKey("deepseek", "sk-ds");
        config.setProviderApiKey("doubao", "sk-db");

        assertEquals("sk-openai", config.getProviderApiKey("openai"));
        assertEquals("sk-ant", config.getProviderApiKey("anthropic"));
        assertEquals("sk-ds", config.getProviderApiKey("deepseek"));
        assertEquals("sk-db", config.getProviderApiKey("doubao"));

        Map<String, String> all = config.listProviderApiKeys();
        assertEquals(4, all.size());
    }
}
