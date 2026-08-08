package com.nousresearch.hermes.improvement;

import com.nousresearch.hermes.memory.store.LocalMemoryStore;
import com.nousresearch.hermes.memory.store.MemoryEntry;
import com.nousresearch.hermes.memory.store.MemoryStoreFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link MemoryVisibilityService}.
 */
class MemoryVisibilityServiceTest {

    @TempDir
    Path tempDir;

    private LocalMemoryStore memoryStore;
    private MemoryVisibilityService service;

    private final String tenantId = "test-tenant";
    private final String userId = "usr_test";

    @BeforeEach
    void setUp() {
        System.setProperty("hermes.home", tempDir.toString());
        memoryStore = new LocalMemoryStore();
        MemoryStoreFactory.set(memoryStore);
        service = new MemoryVisibilityService(memoryStore);
    }

    @AfterEach
    void tearDown() {
        MemoryStoreFactory.reset();
    }

    @Test
    void overviewEmptyWhenNoMemories() {
        var overview = service.getOverview(tenantId, userId);
        assertEquals(0, overview.totalMemories());
    }

    @Test
    void overviewCountsByType() {
        memoryStore.addMemory(MemoryEntry.builder()
                .tenantId(tenantId).userId(userId)
                .type(MemoryEntry.MemoryType.PREFERENCE)
                .content("prefers concise").build());
        memoryStore.addMemory(MemoryEntry.builder()
                .tenantId(tenantId).userId(userId)
                .type(MemoryEntry.MemoryType.PREFERENCE)
                .content("prefers dark mode").build());
        memoryStore.addMemory(MemoryEntry.builder()
                .tenantId(tenantId).userId(userId)
                .type(MemoryEntry.MemoryType.FACT)
                .content("uses Java 21").build());

        var overview = service.getOverview(tenantId, userId);
        assertEquals(2, overview.preferences());
        assertEquals(1, overview.facts());
        assertTrue(overview.byType().containsKey("PREFERENCE"));
        assertEquals(2, overview.byType().get("PREFERENCE"));
    }

    @Test
    void listByTypeFiltersCorrectly() {
        memoryStore.addMemory(MemoryEntry.builder()
                .tenantId(tenantId).userId(userId)
                .type(MemoryEntry.MemoryType.PREFERENCE)
                .content("prefers concise").build());
        memoryStore.addMemory(MemoryEntry.builder()
                .tenantId(tenantId).userId(userId)
                .type(MemoryEntry.MemoryType.FACT)
                .content("uses Java 21").build());

        var prefs = service.listByType(tenantId, userId, MemoryEntry.MemoryType.PREFERENCE, 0, 10);
        assertEquals(1, prefs.size());
        assertTrue(prefs.get(0).getContent().contains("concise"));
    }

    @Test
    void searchReturnsMatchingMemories() {
        memoryStore.addMemory(MemoryEntry.builder()
                .tenantId(tenantId).userId(userId)
                .type(MemoryEntry.MemoryType.PREFERENCE)
                .content("prefers concise responses").build());
        memoryStore.addMemory(MemoryEntry.builder()
                .tenantId(tenantId).userId(userId)
                .type(MemoryEntry.MemoryType.FACT)
                .content("uses PostgreSQL database").build());

        var results = service.search(tenantId, userId, "concise");
        assertFalse(results.isEmpty());
    }

    @Test
    void getPreferencesReturnsOnlyPreferences() {
        memoryStore.addMemory(MemoryEntry.builder()
                .tenantId(tenantId).userId(userId)
                .type(MemoryEntry.MemoryType.PREFERENCE)
                .content("response_style: concise").build());
        memoryStore.addMemory(MemoryEntry.builder()
                .tenantId(tenantId).userId(userId)
                .type(MemoryEntry.MemoryType.FACT)
                .content("uses Java 21").build());

        var prefs = service.getPreferences(tenantId, userId);
        assertEquals(1, prefs.size());
        assertEquals(MemoryEntry.MemoryType.PREFERENCE, prefs.get(0).getType());
    }

    @Test
    void userIsolation() {
        memoryStore.addMemory(MemoryEntry.builder()
                .tenantId(tenantId).userId("userA")
                .type(MemoryEntry.MemoryType.PREFERENCE)
                .content("userA pref").build());
        memoryStore.addMemory(MemoryEntry.builder()
                .tenantId(tenantId).userId("userB")
                .type(MemoryEntry.MemoryType.PREFERENCE)
                .content("userB pref").build());

        var userAOverview = service.getOverview(tenantId, "userA");
        assertEquals(1, userAOverview.totalMemories());

        var userBOverview = service.getOverview(tenantId, "userB");
        assertEquals(1, userBOverview.totalMemories());
    }

    @Test
    void editReturnsTrue() {
        assertTrue(service.edit(tenantId, userId, "mem_001", "new content"));
    }

    @Test
    void deleteReturnsTrue() {
        assertTrue(service.delete(tenantId, userId, "mem_001"));
    }

    @Test
    void getSessionStatsReturnsZerosWhenNoData() {
        var stats = service.getSessionStats(tenantId, "ses_001");
        assertNotNull(stats);
        assertEquals("ses_001", stats.sessionId());
    }
}
