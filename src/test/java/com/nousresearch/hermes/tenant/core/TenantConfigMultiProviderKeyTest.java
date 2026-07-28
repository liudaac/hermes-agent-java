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

    // ============ providerToEnvKey ============

    // ============ Key Source Resolution ============

    // ============ Validation ============

}
