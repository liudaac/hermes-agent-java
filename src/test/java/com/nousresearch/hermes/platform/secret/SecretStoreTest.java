package com.nousresearch.hermes.platform.secret;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * B7: SecretStore implementation tests.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SecretStoreTest {

    // ============ InMemorySecretStore ============

    @Test
    @Order(1)
    @DisplayName("InMemory: set and get secret")
    void inMemory_setGet() {
        SecretStore store = new InMemorySecretStore();
        store.setSecret("tenant-A", "OPENAI_API_KEY", "sk-xxx");
        assertEquals("sk-xxx", store.getSecret("tenant-A", "OPENAI_API_KEY"));
    }

    @Test
    @Order(2)
    @DisplayName("InMemory: get returns null for missing key")
    void inMemory_missingKey() {
        SecretStore store = new InMemorySecretStore();
        assertNull(store.getSecret("tenant-A", "NOPE"));
    }

    @Test
    @Order(3)
    @DisplayName("InMemory: remove secret")
    void inMemory_remove() {
        SecretStore store = new InMemorySecretStore();
        store.setSecret("t1", "KEY", "val");
        assertTrue(store.removeSecret("t1", "KEY"));
        assertNull(store.getSecret("t1", "KEY"));
        assertFalse(store.removeSecret("t1", "KEY"));
    }

    @Test
    @Order(4)
    @DisplayName("InMemory: list secrets (keys only, no values)")
    void inMemory_list() {
        SecretStore store = new InMemorySecretStore();
        store.setSecret("t1", "OPENAI_API_KEY", "sk-1");
        store.setSecret("t1", "ANTHROPIC_API_KEY", "sk-2");

        var keys = store.listSecrets("t1");
        assertEquals(2, keys.size());
        assertTrue(keys.contains("OPENAI_API_KEY"));
        assertTrue(keys.contains("ANTHROPIC_API_KEY"));
    }

    // ============ FileSecretStore ============

    // ============ VaultSecretStore stub ============

}
