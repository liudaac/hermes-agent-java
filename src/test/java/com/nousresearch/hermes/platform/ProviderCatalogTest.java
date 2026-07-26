package com.nousresearch.hermes.platform;

import org.junit.jupiter.api.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * B3: ProviderCatalog unit tests.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProviderCatalogTest {

    @Test
    @Order(1)
    @DisplayName("withDefaults creates catalog with 8 providers")
    void withDefaults_hasAllProviders() {
        ProviderCatalog catalog = ProviderCatalog.withDefaults();
        assertEquals(8, catalog.listProviderIds().size());
    }

    @Test
    @Order(2)
    @DisplayName("isRegistered returns true for known providers")
    void isRegistered_knownProviders() {
        ProviderCatalog catalog = ProviderCatalog.withDefaults();
        assertTrue(catalog.isRegistered("openai"));
        assertTrue(catalog.isRegistered("anthropic"));
        assertTrue(catalog.isRegistered("openrouter"));
        assertTrue(catalog.isRegistered("deepseek"));
        assertTrue(catalog.isRegistered("doubao"));
        assertTrue(catalog.isRegistered("moonshot"));
        assertTrue(catalog.isRegistered("minimax"));
        assertTrue(catalog.isRegistered("ollama"));
    }

    @Test
    @Order(3)
    @DisplayName("isRegistered returns false for unknown provider")
    void isRegistered_unknown() {
        ProviderCatalog catalog = ProviderCatalog.withDefaults();
        assertFalse(catalog.isRegistered("unknown-provider"));
        assertFalse(catalog.isRegistered(null));
    }

    @Test
    @Order(4)
    @DisplayName("isRegistered is case-insensitive")
    void isRegistered_caseInsensitive() {
        ProviderCatalog catalog = ProviderCatalog.withDefaults();
        assertTrue(catalog.isRegistered("OpenAI"));
        assertTrue(catalog.isRegistered("ANTHROPIC"));
        assertTrue(catalog.isRegistered("DeepSeek"));
    }

    @Test
    @Order(5)
    @DisplayName("getDefaultBaseUrl returns correct URLs")
    void getDefaultBaseUrl_knownProviders() {
        ProviderCatalog catalog = ProviderCatalog.withDefaults();
        assertEquals("https://api.openai.com/v1", catalog.getDefaultBaseUrl("openai"));
        assertEquals("https://api.anthropic.com/v1", catalog.getDefaultBaseUrl("anthropic"));
        assertEquals("https://api.deepseek.com/v1", catalog.getDefaultBaseUrl("deepseek"));
        assertEquals("http://localhost:11434/v1", catalog.getDefaultBaseUrl("ollama"));
    }

    @Test
    @Order(6)
    @DisplayName("getDefaultBaseUrl returns null for unknown provider")
    void getDefaultBaseUrl_unknown() {
        ProviderCatalog catalog = ProviderCatalog.withDefaults();
        assertNull(catalog.getDefaultBaseUrl("unknown"));
    }

    @Test
    @Order(7)
    @DisplayName("getDefaultBaseUrlOrFallback returns openrouter for unknown")
    void getDefaultBaseUrlOrFallback() {
        ProviderCatalog catalog = ProviderCatalog.withDefaults();
        assertEquals("https://api.openai.com/v1", catalog.getDefaultBaseUrlOrFallback("openai"));
        assertEquals("https://openrouter.ai/api/v1", catalog.getDefaultBaseUrlOrFallback("unknown"));
    }

    @Test
    @Order(8)
    @DisplayName("isAllowed checks key source permissions")
    void isAllowed_keySource() {
        ProviderCatalog catalog = ProviderCatalog.withDefaults();

        // openai allows both tenant and platform keys
        assertTrue(catalog.isAllowed("openai", "tenant"));
        assertTrue(catalog.isAllowed("openai", "platform"));
        assertTrue(catalog.isAllowed("openai", "hybrid"));

        // ollama allows tenant keys but not platform keys
        assertTrue(catalog.isAllowed("ollama", "tenant"));
        assertFalse(catalog.isAllowed("ollama", "platform"));
        assertTrue(catalog.isAllowed("ollama", "hybrid"));  // hybrid = either

        // unknown provider
        assertFalse(catalog.isAllowed("unknown", "tenant"));
        assertFalse(catalog.isAllowed("unknown", "platform"));
    }

    @Test
    @Order(9)
    @DisplayName("listProviderIds returns ordered list")
    void listProviderIds_ordered() {
        ProviderCatalog catalog = ProviderCatalog.withDefaults();
        List<String> ids = catalog.listProviderIds();
        assertEquals("openai", ids.get(0));
        assertEquals(8, ids.size());
    }

    @Test
    @Order(10)
    @DisplayName("getProvider returns Provider record with fields")
    void getProvider_fields() {
        ProviderCatalog catalog = ProviderCatalog.withDefaults();
        ProviderCatalog.Provider openai = catalog.getProvider("openai");
        assertNotNull(openai);
        assertEquals("openai", openai.id());
        assertEquals("OpenAI", openai.displayName());
        assertEquals("https://api.openai.com/v1", openai.defaultBaseUrl());
        assertTrue(openai.allowTenantKeys());
        assertTrue(openai.allowPlatformKeys());
        assertFalse(openai.supportedModels().isEmpty());
        assertTrue(openai.supportedModels().contains("gpt-4o"));
    }

    @Test
    @Order(11)
    @DisplayName("listProviders returns all providers")
    void listProviders_all() {
        ProviderCatalog catalog = ProviderCatalog.withDefaults();
        assertEquals(8, catalog.listProviders().size());
    }

    @Test
    @Order(12)
    @DisplayName("custom catalog via builder")
    void customCatalog() {
        ProviderCatalog catalog = ProviderCatalog.builder()
            .add("custom-provider", "Custom", "https://custom.api.com/v1",
                true, false, List.of("custom-model"))
            .build();

        assertEquals(1, catalog.listProviderIds().size());
        assertTrue(catalog.isRegistered("custom-provider"));
        assertEquals("https://custom.api.com/v1", catalog.getDefaultBaseUrl("custom-provider"));
        assertFalse(catalog.isRegistered("openai"));  // not in custom catalog
    }

    @Test
    @Order(13)
    @DisplayName("addDefaults can be combined with custom providers")
    void defaultsPlusCustom() {
        ProviderCatalog catalog = ProviderCatalog.builder()
            .addDefaults()
            .add("custom", "Custom", "https://custom.api.com/v1",
                true, true, List.of())
            .build();

        assertEquals(9, catalog.listProviderIds().size());
        assertTrue(catalog.isRegistered("openai"));
        assertTrue(catalog.isRegistered("custom"));
    }

    @Test
    @Order(14)
    @DisplayName("Provider record validates inputs")
    void providerRecordValidation() {
        assertThrows(IllegalArgumentException.class, () ->
            new ProviderCatalog.Provider("", "Empty", "url", true, true, List.of()));
        assertThrows(IllegalArgumentException.class, () ->
            new ProviderCatalog.Provider(null, "Null", "url", true, true, List.of()));
    }

    @Test
    @Order(15)
    @DisplayName("empty catalog has no providers")
    void emptyCatalog() {
        ProviderCatalog catalog = ProviderCatalog.empty();
        assertTrue(catalog.listProviderIds().isEmpty());
        assertFalse(catalog.isRegistered("openai"));
    }

    @Test
    @Order(16)
    @DisplayName("Provider record supportedModels is immutable")
    void providerRecordImmutable() {
        ProviderCatalog.Provider p = new ProviderCatalog.Provider(
            "test", "Test", "https://test.com/v1", true, true,
            List.of("model-a", "model-b"));
        assertEquals(2, p.supportedModels().size());
        // List.of() is already immutable
        assertThrows(UnsupportedOperationException.class, () ->
            p.supportedModels().add("model-c"));
    }
}
