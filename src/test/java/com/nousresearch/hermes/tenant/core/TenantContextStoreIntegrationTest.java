package com.nousresearch.hermes.tenant.core;

import com.nousresearch.hermes.memory.store.*;
import com.nousresearch.hermes.skills.store.*;
import org.junit.jupiter.api.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for centralised MemoryStore + SkillStore wiring into TenantContext.
 */
class TenantContextStoreIntegrationTest {

    @Test
    @DisplayName("TenantContext exposes central MemoryStore and SkillStore")
    void testCentralStoresAvailable() {
        // The factories return Local implementations by default (no Redis/Postgres configured)
        MemoryStore memoryStore = MemoryStoreFactory.get();
        assertNotNull(memoryStore);
        assertInstanceOf(LocalMemoryStore.class, memoryStore);

        SkillStore skillStore = SkillStoreFactory.get();
        assertNotNull(skillStore);
        assertInstanceOf(LocalSkillStore.class, skillStore);
    }

    @Test
    @DisplayName("MemoryStore session append + recall works end-to-end")
    void testSessionMemoryRoundTrip() {
        LocalMemoryStore store = new LocalMemoryStore();

        // Simulate a conversation
        store.appendSessionMessage("test-tenant", "session-1", "user", "What database should I use?");
        store.appendSessionMessage("test-tenant", "session-1", "assistant", "I recommend PostgreSQL for your use case.");
        store.appendSessionMessage("test-tenant", "session-1", "user", "Great, let's use PostgreSQL then.");

        // Recall should return all messages
        List<MemoryStore.MemoryRecall> recalls = store.recallSession(
            "test-tenant", "session-1", "", 10, DecayPolicy.standard());

        assertEquals(3, recalls.size());
        assertEquals(RecallStage.FULL, recalls.get(0).stage());
    }

    @Test
    @DisplayName("MemoryStore long-term search with BM25 finds keyword matches")
    void testLongTermBM25Search() {
        LocalMemoryStore store = new LocalMemoryStore();

        store.addMemory(MemoryEntry.builder()
            .tenantId("test-tenant")
            .type(MemoryEntry.MemoryType.DECISION)
            .content("Team decided to use Redis for session caching")
            .category("tech_choice")
            .build());

        store.addMemory(MemoryEntry.builder()
            .tenantId("test-tenant")
            .type(MemoryEntry.MemoryType.PREFERENCE)
            .content("User prefers morning standup meetings")
            .category("schedule")
            .build());

        store.addMemory(MemoryEntry.builder()
            .tenantId("test-tenant")
            .type(MemoryEntry.MemoryType.FACT)
            .content("Project deadline is August 15th")
            .category("timeline")
            .build());

        // Search for Redis-related memories
        List<MemoryEntry> redisResults = store.searchMemories("test-tenant", null, "Redis", 10);
        assertFalse(redisResults.isEmpty());
        assertTrue(redisResults.stream().anyMatch(m -> m.getContent().contains("Redis")));

        // Search for schedule-related memories
        List<MemoryEntry> scheduleResults = store.searchMemories("test-tenant", null, "morning standup", 10);
        assertFalse(scheduleResults.isEmpty());
        assertTrue(scheduleResults.stream().anyMatch(m -> m.getContent().contains("standup")));
    }

    @Test
    @DisplayName("SkillStore register + discover + version management end-to-end")
    void testSkillLifecycle() {
        LocalSkillStore store = new LocalSkillStore();

        // Register
        String id = store.register("test-tenant", new SkillStore.SkillRegistration(
            "db-connector",
            "Database connector skill",
            SkillStore.SkillScope.PRIVATE,
            SkillStore.SkillType.CUSTOM,
            new SkillStore.SkillConfig("v1 content", null, List.of("db.read"), null)
        ));
        assertNotNull(id);

        // Discover
        SkillStore.SkillInfo info = store.findByName("test-tenant", "db-connector");
        assertNotNull(info);
        assertEquals("1.0.0", info.currentVersion());
        assertTrue(info.enabled());

        // Publish v2
        store.publishVersion("test-tenant", id, "2.0.0",
            new SkillStore.SkillConfig("v2 with connection pooling", null, List.of("db.read", "db.write"), null));

        // Verify active version
        SkillStore.SkillConfig active = store.getActiveVersion("test-tenant", id);
        assertNotNull(active);
        assertEquals("v2 with connection pooling", active.content());

        // Rollback
        store.rollback("test-tenant", id, "1.0.0");
        SkillStore.SkillConfig rolledBack = store.getActiveVersion("test-tenant", id);
        assertNotNull(rolledBack);
        assertEquals("v1 content", rolledBack.content());

        // List versions
        List<String> versions = store.listVersions("test-tenant", id);
        assertEquals(2, versions.size());
    }

    @Test
    @DisplayName("Decay policy promotes facts from short-term to long-term")
    void testDecayPromotion() {
        LocalMemoryStore store = new LocalMemoryStore();

        // Ultra-short decay policy for testing
        DecayPolicy fast = DecayPolicy.builder()
            .fullWindow(java.time.Duration.ofMillis(1))
            .warmWindow(java.time.Duration.ofMillis(2))
            .coolWindow(java.time.Duration.ofMillis(3))
            .summaryBatchSize(1)
            .build();

        // Add a message with an important decision
        store.appendSessionMessage("test-tenant", "dec-sess", "user",
            "We decided to use Kafka for event streaming");

        // Wait for message to age past fullWindow
        try { Thread.sleep(5); } catch (InterruptedException ignored) {}

        // First decay: compress to COOL
        store.runDecayCycle("test-tenant", "dec-sess", fast,
            msgs -> "Decision: use Kafka for event streaming",
            (summary, max) -> List.of("Team chose Kafka for event streaming"));

        // Wait for COOL to age
        try { Thread.sleep(5); } catch (InterruptedException ignored) {}

        // Second decay: evict from COOL, extract facts
        MemoryStore.DecayResult result = store.runDecayCycle(
            "test-tenant", "dec-sess", fast,
            msgs -> "unused",
            (summary, max) -> List.of("Team chose Kafka for event streaming"));

        assertTrue(result.evictedFromCool() > 0);
        assertTrue(result.factsExtracted() > 0);

        // Verify fact is now in long-term memory
        List<MemoryEntry> longTerm = store.searchMemories("test-tenant", null, "Kafka", 10);
        assertTrue(longTerm.stream().anyMatch(m -> m.getContent().contains("Kafka")));
    }

    @Test
    @DisplayName("SHARED skill is visible to other tenants")
    void testSharedSkillVisibility() {
        LocalSkillStore store = new LocalSkillStore();

        // Tenant A registers a shared skill
        store.register("tenant-a", new SkillStore.SkillRegistration(
            "common-tool",
            "A shared utility skill",
            SkillStore.SkillScope.SHARED,
            null, null
        ));

        // Tenant B should see it
        List<SkillStore.SkillInfo> bSkills = store.list("tenant-b", SkillStore.SkillScope.SHARED);
        assertTrue(bSkills.stream().anyMatch(s -> s.name().equals("common-tool")));

        // Tenant B should find it by name
        SkillStore.SkillInfo found = store.findByName("tenant-b", "common-tool");
        assertNotNull(found);
        assertEquals(SkillStore.SkillScope.SHARED, found.scope());
    }

    @Test
    @DisplayName("Memory tenant isolation in search")
    void testMemoryTenantIsolation() {
        LocalMemoryStore store = new LocalMemoryStore();

        store.addMemory(MemoryEntry.builder()
            .tenantId("tenant-a")
            .content("Tenant A's private decision")
            .build());

        store.addMemory(MemoryEntry.builder()
            .tenantId("tenant-b")
            .content("Tenant B's private decision")
            .build());

        List<MemoryEntry> aResults = store.searchMemories("tenant-a", null, "private", 10);
        List<MemoryEntry> bResults = store.searchMemories("tenant-b", null, "private", 10);

        assertTrue(aResults.stream().allMatch(m -> m.getTenantId().equals("tenant-a")));
        assertTrue(bResults.stream().allMatch(m -> m.getTenantId().equals("tenant-b")));
        assertFalse(aResults.stream().anyMatch(m -> m.getContent().contains("Tenant B")));
        assertFalse(bResults.stream().anyMatch(m -> m.getContent().contains("Tenant A")));
    }

    @Test
    @DisplayName("MemorySkillMetrics records operations")
    void testMetricsRecording() {
        MemorySkillMetrics metrics = MemorySkillMetrics.getInstance();
        metrics.reset();

        LocalMemoryStore store = new LocalMemoryStore();
        store.appendSessionMessage("metrics-tenant", "s1", "user", "test message");
        store.addMemory(MemoryEntry.builder()
            .tenantId("metrics-tenant")
            .content("test long-term memory")
            .build());

        java.util.Map<String, Long> memSummary = metrics.getMemorySummary("metrics-tenant");
        assertTrue(memSummary.getOrDefault("memory_session_writes_total", 0L) > 0);
        assertTrue(memSummary.getOrDefault("memory_longterm_writes_total", 0L) > 0);
    }
}
