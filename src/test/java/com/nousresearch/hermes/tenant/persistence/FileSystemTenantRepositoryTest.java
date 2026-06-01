package com.nousresearch.hermes.tenant.persistence;

import com.nousresearch.hermes.tenant.core.TenantContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FileSystemTenantRepositoryTest {

    @TempDir
    Path tempDir;

    @Test
    void savesAndLoadsTenantState() throws Exception {
        FileSystemTenantRepository repo = new FileSystemTenantRepository(tempDir);
        TenantStateRepository.TenantStateSnapshot state = snapshot("tenant-a", 1);

        repo.saveState("tenant-a", state).get();

        var loaded = repo.loadState("tenant-a").get();
        assertTrue(loaded.isPresent());
        assertEquals("tenant-a", loaded.get().tenantId());
        assertEquals(1, loaded.get().version());
        assertTrue(repo.exists("tenant-a").get());
        assertTrue(repo.getLastUpdated("tenant-a").get().isPresent());
        repo.close();
    }

    @Test
    void recoversTenantStateFromBackupWhenPrimaryIsCorrupted() throws Exception {
        FileSystemTenantRepository repo = new FileSystemTenantRepository(tempDir);

        repo.saveState("tenant-a", snapshot("tenant-a", 1)).get();
        repo.saveState("tenant-a", snapshot("tenant-a", 2)).get();

        Path primary = tempDir.resolve("tenants").resolve("tenant-a").resolve("state.json");
        Path backup = tempDir.resolve("tenants").resolve("tenant-a").resolve("state.json.bak");
        assertTrue(Files.exists(primary));
        assertTrue(Files.exists(backup));

        Files.writeString(primary, "{broken-json");

        var loaded = repo.loadState("tenant-a").get();
        assertTrue(loaded.isPresent());
        assertEquals(1, loaded.get().version(), "Backup should contain previous known-good state");
        repo.close();
    }

    @Test
    void savesLoadsListsAndDeletesSessions() throws Exception {
        FileSystemTenantRepository repo = new FileSystemTenantRepository(tempDir);
        TenantStateRepository.SessionState session = new TenantStateRepository.SessionState(
            "session-a",
            "tenant-a",
            Instant.now(),
            Instant.now(),
            Map.of("channel", "test"),
            "context".getBytes()
        );

        repo.saveSession("tenant-a", "session-a", session).get();

        var loaded = repo.loadSession("tenant-a", "session-a").get();
        assertTrue(loaded.isPresent());
        assertEquals("session-a", loaded.get().sessionId());
        assertEquals(Map.of("channel", "test"), loaded.get().metadata());
        assertEquals(java.util.List.of("session-a"), repo.listSessions("tenant-a").get());

        repo.deleteSession("tenant-a", "session-a").get();
        assertTrue(repo.loadSession("tenant-a", "session-a").get().isEmpty());
        repo.close();
    }

    private TenantStateRepository.TenantStateSnapshot snapshot(String tenantId, long version) {
        return new TenantStateRepository.TenantStateSnapshot(
            tenantId,
            TenantContext.State.ACTIVE,
            Instant.now(),
            Instant.now(),
            Map.of("name", tenantId),
            Map.of("requests", 1),
            Map.of("network", "restricted"),
            version
        );
    }
}
