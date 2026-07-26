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

    @Test
    @Order(5)
    @DisplayName("InMemory: hasSecret")
    void inMemory_hasSecret() {
        SecretStore store = new InMemorySecretStore();
        store.setSecret("t1", "KEY", "val");
        assertTrue(store.hasSecret("t1", "KEY"));
        assertFalse(store.hasSecret("t1", "MISSING"));
        assertFalse(store.hasSecret("other-tenant", "KEY"));
    }

    @Test
    @Order(6)
    @DisplayName("InMemory: loadAll returns all secrets")
    void inMemory_loadAll() {
        SecretStore store = new InMemorySecretStore();
        store.setSecret("t1", "A", "1");
        store.setSecret("t1", "B", "2");
        Map<String, String> all = store.loadAll("t1");
        assertEquals(2, all.size());
        assertEquals("1", all.get("A"));
        assertEquals("2", all.get("B"));
    }

    @Test
    @Order(7)
    @DisplayName("InMemory: tenants are isolated")
    void inMemory_tenantIsolation() {
        SecretStore store = new InMemorySecretStore();
        store.setSecret("tenant-A", "KEY", "val-A");
        store.setSecret("tenant-B", "KEY", "val-B");

        assertEquals("val-A", store.getSecret("tenant-A", "KEY"));
        assertEquals("val-B", store.getSecret("tenant-B", "KEY"));
        assertNotEquals(
            store.getSecret("tenant-A", "KEY"),
            store.getSecret("tenant-B", "KEY"));
    }

    @Test
    @Order(8)
    @DisplayName("InMemory: loadFromEnv loads from environment")
    void inMemory_loadFromEnv() {
        InMemorySecretStore store = new InMemorySecretStore();
        store.loadFromEnv("t1", "NONEXISTENT_VAR");
        assertTrue(store.listSecrets("t1").isEmpty());
    }

    // ============ FileSecretStore ============

    @Test
    @Order(9)
    @DisplayName("File: set and get secret (@TempDir)")
    void file_setGet(@TempDir Path dir) {
        SecretStore store = new FileSecretStore(dir);
        store.setSecret("tenant-A", "OPENAI_API_KEY", "sk-xxx");
        assertEquals("sk-xxx", store.getSecret("tenant-A", "OPENAI_API_KEY"));
    }

    @Test
    @Order(10)
    @DisplayName("File: secret persists across instances")
    void file_persistence(@TempDir Path dir) {
        SecretStore store1 = new FileSecretStore(dir);
        store1.setSecret("t1", "KEY", "val1");

        // Create new instance pointing to same dir
        SecretStore store2 = new FileSecretStore(dir);
        assertEquals("val1", store2.getSecret("t1", "KEY"));
    }

    @Test
    @Order(11)
    @DisplayName("File: remove secret")
    void file_remove(@TempDir Path dir) {
        SecretStore store = new FileSecretStore(dir);
        store.setSecret("t1", "KEY", "val");
        assertTrue(store.removeSecret("t1", "KEY"));
        assertNull(store.getSecret("t1", "KEY"));
    }

    @Test
    @Order(12)
    @DisplayName("File: list secrets")
    void file_list(@TempDir Path dir) {
        SecretStore store = new FileSecretStore(dir);
        store.setSecret("t1", "OPENAI_API_KEY", "sk-1");
        store.setSecret("t1", "ANTHROPIC_API_KEY", "sk-2");
        var keys = store.listSecrets("t1");
        assertEquals(2, keys.size());
    }

    @Test
    @Order(13)
    @DisplayName("File: tenants are isolated")
    void file_tenantIsolation(@TempDir Path dir) {
        SecretStore store = new FileSecretStore(dir);
        store.setSecret("tenant-A", "KEY", "val-A");
        store.setSecret("tenant-B", "KEY", "val-B");

        assertEquals("val-A", store.getSecret("tenant-A", "KEY"));
        assertEquals("val-B", store.getSecret("tenant-B", "KEY"));
    }

    @Test
    @Order(14)
    @DisplayName("File: loadAll returns all secrets")
    void file_loadAll(@TempDir Path dir) {
        SecretStore store = new FileSecretStore(dir);
        store.setSecret("t1", "A", "1");
        store.setSecret("t1", "B", "2");
        Map<String, String> all = store.loadAll("t1");
        assertEquals(2, all.size());
    }

    @Test
    @Order(15)
    @DisplayName("File: secrets.env file has 600 permissions")
    void file_permissions(@TempDir Path dir) throws Exception {
        SecretStore store = new FileSecretStore(dir);
        store.setSecret("t1", "KEY", "val");

        Path secretsFile = dir.resolve("t1").resolve("config").resolve("secrets.env");
        assertTrue(java.nio.file.Files.exists(secretsFile));

        // Skip permission check on non-POSIX systems
        if (java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            var perms = java.nio.file.Files.getPosixFilePermissions(secretsFile);
            assertEquals("rw-------",
                java.nio.file.attribute.PosixFilePermissions.toString(perms));
        }
    }

    @Test
    @Order(16)
    @DisplayName("File: empty secrets for unknown tenant")
    void file_unknownTenant(@TempDir Path dir) {
        SecretStore store = new FileSecretStore(dir);
        assertNull(store.getSecret("unknown", "KEY"));
        assertTrue(store.listSecrets("unknown").isEmpty());
        assertTrue(store.loadAll("unknown").isEmpty());
    }

    // ============ VaultSecretStore stub ============

    @Test
    @Order(17)
    @DisplayName("Vault: throws UnsupportedOperationException")
    void vault_notImplemented() {
        VaultSecretStore vault = new VaultSecretStore("http://localhost:8200", "token", "secret/hermes");
        assertThrows(UnsupportedOperationException.class, () -> vault.getSecret("t1", "KEY"));
        assertThrows(UnsupportedOperationException.class, () -> vault.setSecret("t1", "KEY", "val"));
    }

    @Test
    @Order(18)
    @DisplayName("Vault: config fields accessible")
    void vault_config() {
        VaultSecretStore vault = new VaultSecretStore("http://vault:8200", "tok", "secret/hermes");
        assertEquals("http://vault:8200", vault.getVaultAddress());
        assertEquals("secret/hermes", vault.getPathPrefix());
    }
}
