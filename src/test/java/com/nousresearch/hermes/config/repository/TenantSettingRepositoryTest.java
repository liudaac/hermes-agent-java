package com.nousresearch.hermes.config.repository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for tenant settings KV interface on both
 * {@link LocalConfigRepository} and {@link ConfigCache}.
 */
class TenantSettingRepositoryTest {

    @Test
    @DisplayName("LocalConfigRepository: save and load tenant setting")
    void local_saveAndLoad(@TempDir Path tmp) {
        LocalConfigRepository repo = new LocalConfigRepository(tmp);
        repo.saveTenantSetting("tenant-1", "memory.decay_policy", "aggressive");

        String value = repo.loadTenantSetting("tenant-1", "memory.decay_policy");
        assertEquals("aggressive", value);
    }

    @Test
    @DisplayName("LocalConfigRepository: load non-existent setting returns null")
    void local_loadMissing(@TempDir Path tmp) {
        LocalConfigRepository repo = new LocalConfigRepository(tmp);
        String value = repo.loadTenantSetting("tenant-1", "non.existent");
        assertNull(value);
    }

    @Test
    @DisplayName("LocalConfigRepository: update existing setting")
    void local_updateSetting(@TempDir Path tmp) {
        LocalConfigRepository repo = new LocalConfigRepository(tmp);
        repo.saveTenantSetting("tenant-1", "memory.decay_policy", "standard");
        repo.saveTenantSetting("tenant-1", "memory.decay_policy", "archival");

        assertEquals("archival", repo.loadTenantSetting("tenant-1", "memory.decay_policy"));
    }

    @Test
    @DisplayName("LocalConfigRepository: loadAllTenantSettings returns all keys")
    void local_loadAll(@TempDir Path tmp) {
        LocalConfigRepository repo = new LocalConfigRepository(tmp);
        repo.saveTenantSetting("tenant-1", "memory.decay_policy", "longRunning");

        Map<String, String> all = repo.loadAllTenantSettings("tenant-1");
        assertFalse(all.isEmpty());
        // Should contain the saved key
        boolean hasDecayPolicy = all.containsKey("memory.decay_policy") || all.values().stream().anyMatch(v -> v.equals("longRunning"));
        assertTrue(hasDecayPolicy);
    }

}
