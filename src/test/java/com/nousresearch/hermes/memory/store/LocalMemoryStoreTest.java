package com.nousresearch.hermes.memory.store;

import org.junit.jupiter.api.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link LocalMemoryStore}.
 */
class LocalMemoryStoreTest {

    private LocalMemoryStore store;

    @BeforeEach
    void setUp() {
        store = new LocalMemoryStore();
    }

    // ══════════════════════════════════════════════════════════════════
    //  Short-term: Session Memory
    // ══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Append and recall session messages")
    void testAppendAndRecall() {
        store.appendSessionMessage("t1", "s1", "user", "Hello");
        store.appendSessionMessage("t1", "s1", "assistant", "Hi there!");

        List<MemoryStore.MemoryRecall> recalls = store.recallSession(
            "t1", "s1", "", 10, DecayPolicy.standard());

        assertEquals(2, recalls.size());
        assertEquals("Hello", recalls.get(0).content());
        assertEquals("Hi there!", recalls.get(1).content());
        assertEquals(RecallStage.FULL, recalls.get(0).stage());
    }

    @Test
    @DisplayName("Session isolation by tenant")
    void testTenantIsolation() {
        store.appendSessionMessage("t1", "s1", "user", "Tenant 1 message");
        store.appendSessionMessage("t2", "s1", "user", "Tenant 2 message");

        List<MemoryStore.MemoryRecall> t1 = store.recallSession(
            "t1", "s1", "", 10, DecayPolicy.standard());
        List<MemoryStore.MemoryRecall> t2 = store.recallSession(
            "t2", "s1", "", 10, DecayPolicy.standard());

        assertEquals(1, t1.size());
        assertEquals("Tenant 1 message", t1.get(0).content());
        assertEquals(1, t2.size());
        assertEquals("Tenant 2 message", t2.get(0).content());
    }

    @Test
    @DisplayName("Recall with query ranks relevant messages higher")
    void testRecallWithQuery() {
        store.appendSessionMessage("t1", "s1", "user", "I prefer PostgreSQL");
        store.appendSessionMessage("t1", "s1", "user", "The weather is nice today");
        store.appendSessionMessage("t1", "s1", "assistant", "PostgreSQL is a great choice");

        List<MemoryStore.MemoryRecall> recalls = store.recallSession(
            "t1", "s1", "PostgreSQL", 10, DecayPolicy.standard());

        // Messages containing "PostgreSQL" should rank higher
        assertTrue(recalls.get(0).score() >= recalls.get(recalls.size() - 1).score());
        assertTrue(recalls.get(0).content().contains("PostgreSQL"));
    }

    @Test
    @DisplayName("Clear session removes all messages")
    void testClearSession() {
        store.appendSessionMessage("t1", "s1", "user", "Hello");
        store.clearSession("t1", "s1");

        List<MemoryStore.MemoryRecall> recalls = store.recallSession(
            "t1", "s1", "", 10, DecayPolicy.standard());
        assertTrue(recalls.isEmpty());
    }

    @Test
    @DisplayName("Session stats report correct counts")
    void testSessionStats() {
        store.appendSessionMessage("t1", "s1", "user", "msg1");
        store.appendSessionMessage("t1", "s1", "user", "msg2");

        MemoryStore.SessionMemoryStats stats = store.getSessionStats("t1", "s1");
        assertEquals(2, stats.fullCount());
        assertEquals(0, stats.coolCount());
        assertEquals(0, stats.evictedCount());
        assertNotNull(stats.earliestMessage());
        assertNotNull(stats.latestMessage());
    }

    // ══════════════════════════════════════════════════════════════════
    //  Decay
    // ══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Decay cycle with no-op summariser returns zero compression")
    void testDecayNoOp() {
        store.appendSessionMessage("t1", "s1", "user", "Recent message");

        MemoryStore.DecayResult result = store.runDecayCycle(
            "t1", "s1", DecayPolicy.standard(),
            msgs -> "summary",
            (summary, max) -> List.of("fact1")
        );

        // Message is recent (within fullWindow), so nothing should be compressed
        assertEquals(0, result.compressedToCool());
        assertEquals(0, result.evictedFromCool());
    }

    @Test
    @DisplayName("Decay cycle compresses old messages to COOL summaries")
    void testDecayCompressesOldMessages() {
        // Use aggressive policy: fullWindow=2h, warmWindow=8h, coolWindow=24h
        // We can't wait 2h, so we'll test the logic with a custom ultra-short policy
        DecayPolicy ultraShort = DecayPolicy.builder()
            .fullWindow(Duration.ofMillis(1))
            .warmWindow(Duration.ofMillis(2))
            .coolWindow(Duration.ofMillis(5))
            .summaryBatchSize(1)
            .build();

        store.appendSessionMessage("t1", "s1", "user", "Old message 1");
        store.appendSessionMessage("t1", "s1", "assistant", "Old reply 1");

        // Sleep to ensure messages are older than fullWindow
        try { Thread.sleep(10); } catch (InterruptedException ignored) {}

        MemoryStore.DecayResult result = store.runDecayCycle(
            "t1", "s1", ultraShort,
            msgs -> "Summary of " + msgs.size() + " messages",
            (summary, max) -> List.of("extracted fact")
        );

        assertTrue(result.compressedToCool() > 0);
        assertEquals(0, result.evictedFromCool()); // Summary just created, not old enough to evict

        // Verify recall now includes COOL summary
        List<MemoryStore.MemoryRecall> recalls = store.recallSession(
            "t1", "s1", "", 10, ultraShort);
        assertTrue(recalls.stream().anyMatch(r -> r.summary() && r.stage() == RecallStage.COOL));
    }

    @Test
    @DisplayName("Decay cycle evicts old summaries and extracts facts to long-term")
    void testDecayEvictsAndExtractsFacts() {
        DecayPolicy ultraShort = DecayPolicy.builder()
            .fullWindow(Duration.ofMillis(1))
            .warmWindow(Duration.ofMillis(2))
            .coolWindow(Duration.ofMillis(3))
            .summaryBatchSize(1)
            .extractFactsOnEvict(true)
            .maxFactsPerEviction(3)
            .build();

        store.appendSessionMessage("t1", "s1", "user", "Important decision: use Redis");

        // First decay: compress to COOL
        try { Thread.sleep(5); } catch (InterruptedException ignored) {}
        store.runDecayCycle("t1", "s1", ultraShort,
            msgs -> "Decided to use Redis for caching",
            (summary, max) -> List.of("Decision: use Redis for caching"));

        // Second decay: evict from COOL and extract facts
        try { Thread.sleep(5); } catch (InterruptedException ignored) {}
        MemoryStore.DecayResult result = store.runDecayCycle(
            "t1", "s1", ultraShort,
            msgs -> "unused",
            (summary, max) -> List.of("Decision: use Redis for caching"));

        assertTrue(result.evictedFromCool() > 0);
        assertTrue(result.factsExtracted() > 0);
        assertTrue(result.extractedFacts().contains("Decision: use Redis for caching"));

        // Verify fact is now in long-term memory
        List<MemoryEntry> longTerm = store.searchMemories("t1", null, "Redis", 10);
        assertTrue(longTerm.stream().anyMatch(m -> m.getContent().contains("Redis")));
    }

    // ══════════════════════════════════════════════════════════════════
    //  Long-term Memory
    // ══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Add and search long-term memories")
    void testAddAndSearchMemories() {
        store.addMemory(MemoryEntry.builder()
            .tenantId("t1")
            .type(MemoryEntry.MemoryType.PREFERENCE)
            .content("User prefers concise answers")
            .category("communication")
            .build());

        store.addMemory(MemoryEntry.builder()
            .tenantId("t1")
            .type(MemoryEntry.MemoryType.DECISION)
            .content("Team chose PostgreSQL for the database")
            .category("tech_choice")
            .build());

        store.addMemory(MemoryEntry.builder()
            .tenantId("t1")
            .type(MemoryEntry.MemoryType.FACT)
            .content("User works at Acme Inc")
            .category("profile")
            .build());

        // Search for PostgreSQL-related memories
        List<MemoryEntry> results = store.searchMemories("t1", null, "PostgreSQL", 10);
        assertFalse(results.isEmpty());
        assertTrue(results.stream().anyMatch(m -> m.getContent().contains("PostgreSQL")));
    }

    @Test
    @DisplayName("Long-term memory tenant isolation")
    void testLongTermTenantIsolation() {
        store.addMemory(MemoryEntry.builder()
            .tenantId("t1")
            .content("Tenant 1 secret")
            .build());
        store.addMemory(MemoryEntry.builder()
            .tenantId("t2")
            .content("Tenant 2 secret")
            .build());

        List<MemoryEntry> t1Results = store.searchMemories("t1", null, "secret", 10);
        List<MemoryEntry> t2Results = store.searchMemories("t2", null, "secret", 10);

        assertTrue(t1Results.stream().allMatch(m -> m.getTenantId().equals("t1")));
        assertTrue(t2Results.stream().allMatch(m -> m.getTenantId().equals("t2")));
    }

    @Test
    @DisplayName("Invalidate memory marks it as no longer valid")
    void testInvalidateMemory() {
        String id = store.addMemory(MemoryEntry.builder()
            .tenantId("t1")
            .content("User's current project is Alpha")
            .build());

        store.invalidateMemory(id);

        List<MemoryEntry> results = store.searchMemories("t1", null, "Alpha", 10);
        // Invalidated memory should not appear in search results
        assertTrue(results.stream().noneMatch(m -> id.equals(m.getId())));
    }

    @Test
    @DisplayName("Delete memory permanently removes it")
    void testDeleteMemory() {
        String id = store.addMemory(MemoryEntry.builder()
            .tenantId("t1")
            .content("Temporary fact")
            .build());

        store.deleteMemory(id);
        List<MemoryEntry> results = store.searchMemories("t1", null, "Temporary", 10);
        assertTrue(results.isEmpty());
    }

    @Test
    @DisplayName("BM25 retrieval finds keyword matches")
    void testBM25Retrieval() {
        store.addMemory(MemoryEntry.builder()
            .tenantId("t1")
            .content("ORDER_ID_8842 was processed successfully")
            .build());
        store.addMemory(MemoryEntry.builder()
            .tenantId("t1")
            .content("The weather forecast looks good for tomorrow")
            .build());

        List<MemoryEntry> results = store.searchMemories("t1", null, "ORDER_ID_8842", 10);
        assertFalse(results.isEmpty());
        assertTrue(results.get(0).getContent().contains("ORDER_ID_8842"));
    }

    // ══════════════════════════════════════════════════════════════════
    //  Agent Experience
    // ══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Add and retrieve agent experiences")
    void testAgentExperience() {
        store.addAgentExperience("t1", "agent-1", "workflow", "Always verify file paths before writing");
        store.addAgentExperience("t1", "agent-1", "workflow", "Check Redis connection before retry");
        store.addAgentExperience("t1", "agent-1", "learning", "User prefers morning updates");

        List<String> workflow = store.getAgentExperiences("t1", "agent-1", "workflow", 10);
        assertEquals(2, workflow.size());

        List<String> learning = store.getAgentExperiences("t1", "agent-1", "learning", 10);
        assertEquals(1, learning.size());
        assertEquals("User prefers morning updates", learning.get(0));
    }

    @Test
    @DisplayName("Agent experience tenant isolation")
    void testAgentExperienceIsolation() {
        store.addAgentExperience("t1", "agent-1", "workflow", "Tenant 1 experience");
        store.addAgentExperience("t2", "agent-1", "workflow", "Tenant 2 experience");

        List<String> t1 = store.getAgentExperiences("t1", "agent-1", "workflow", 10);
        List<String> t2 = store.getAgentExperiences("t2", "agent-1", "workflow", 10);

        assertEquals(1, t1.size());
        assertEquals("Tenant 1 experience", t1.get(0));
        assertEquals(1, t2.size());
        assertEquals("Tenant 2 experience", t2.get(0));
    }

    // ══════════════════════════════════════════════════════════════════
    //  DecayPolicy presets
    // ══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("DecayPolicy presets have correct windows")
    void testDecayPolicyPresets() {
        assertEquals(Duration.ofHours(2), DecayPolicy.aggressive().getFullWindow());
        assertEquals(Duration.ofHours(8), DecayPolicy.aggressive().getWarmWindow());

        assertEquals(Duration.ofDays(1), DecayPolicy.standard().getFullWindow());
        assertEquals(Duration.ofDays(3), DecayPolicy.standard().getWarmWindow());
        assertEquals(Duration.ofDays(7), DecayPolicy.standard().getCoolWindow());

        assertEquals(Duration.ofDays(3), DecayPolicy.longRunning().getFullWindow());
        assertEquals(Duration.ofDays(7), DecayPolicy.longRunning().getWarmWindow());
        assertEquals(Duration.ofDays(30), DecayPolicy.longRunning().getCoolWindow());

        assertEquals(Duration.ofDays(7), DecayPolicy.archival().getFullWindow());
        assertEquals(Duration.ofDays(30), DecayPolicy.archival().getWarmWindow());
        assertEquals(Duration.ofDays(90), DecayPolicy.archival().getCoolWindow());
    }

    @Test
    @DisplayName("DecayPolicy classifyStage and stageWeight")
    void testClassifyStage() {
        DecayPolicy policy = DecayPolicy.standard();

        assertEquals(RecallStage.FULL, policy.classifyStage(0));
        assertEquals(RecallStage.FULL, policy.classifyStage(Duration.ofHours(23).toMillis()));
        assertEquals(RecallStage.WARM, policy.classifyStage(Duration.ofDays(2).toMillis()));
        assertEquals(RecallStage.COOL, policy.classifyStage(Duration.ofDays(5).toMillis()));
        assertEquals(RecallStage.EVICT, policy.classifyStage(Duration.ofDays(10).toMillis()));

        assertEquals(1.0, policy.stageWeight(RecallStage.FULL));
        assertEquals(0.7, policy.stageWeight(RecallStage.WARM));
        assertEquals(0.3, policy.stageWeight(RecallStage.COOL));
        assertEquals(0.0, policy.stageWeight(RecallStage.EVICT));
    }

    @Test
    @DisplayName("Custom DecayPolicy can be built")
    void testCustomDecayPolicy() {
        DecayPolicy custom = DecayPolicy.builder()
            .fullWindow(Duration.ofHours(6))
            .warmWindow(Duration.ofHours(12))
            .coolWindow(Duration.ofHours(48))
            .warmWeight(0.5)
            .coolWeight(0.1)
            .summaryBatchSize(3)
            .extractFactsOnEvict(false)
            .build();

        assertEquals(Duration.ofHours(6), custom.getFullWindow());
        assertEquals(0.5, custom.getWarmWeight());
        assertEquals(3, custom.getSummaryBatchSize());
        assertFalse(custom.isExtractFactsOnEvict());
    }

    // ══════════════════════════════════════════════════════════════════
    //  Factory
    // ══════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("MemoryStoreFactory returns LocalMemoryStore by default")
    void testFactoryDefault() {
        MemoryStoreFactory.reset();
        MemoryStore store = MemoryStoreFactory.get();
        assertInstanceOf(LocalMemoryStore.class, store);
    }

    @Test
    @DisplayName("MemoryStoreFactory accepts override for testing")
    void testFactoryOverride() {
        LocalMemoryStore custom = new LocalMemoryStore();
        MemoryStoreFactory.set(custom);
        assertSame(custom, MemoryStoreFactory.get());
        MemoryStoreFactory.reset();
    }
}
