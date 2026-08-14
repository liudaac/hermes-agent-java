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


}
