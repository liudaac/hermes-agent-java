package com.nousresearch.hermes.tenant.core;

import com.nousresearch.hermes.agent.TenantAwareAIAgent;
import com.nousresearch.hermes.config.HermesConfig;
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
 * Verifies user-dimension memory isolation in TenantAIAgent.
 *
 * <p>Sprint 1: userId is now threaded through processMessage ->
 * MemoryStore.searchMemories, ensuring different users in the same tenant
 * have isolated long-term memory retrieval.</p>
 */
class UserDimensionMemoryIsolationTest {

    @TempDir
    Path tempDir;

    private LocalMemoryStore memoryStore;
    private final String tenantId = "test-tenant";
    private final String userA = "user-A";
    private final String userB = "user-B";

    @BeforeEach
    void setUp() {
        System.setProperty("hermes.home", tempDir.toString());
        memoryStore = new LocalMemoryStore();
        MemoryStoreFactory.set(memoryStore);
    }

    @AfterEach
    void tearDown() {
        MemoryStoreFactory.reset();
    }

    @Test
    void shouldRetrieveOnlyUserSpecificMemories() {
        // Given: user A and user B have different preferences
        memoryStore.addMemory(MemoryEntry.builder()
            .tenantId(tenantId).userId(userA)
            .type(MemoryEntry.MemoryType.PREFERENCE)
            .content("User A prefers concise responses")
            .category("response_style")
            .build());

        memoryStore.addMemory(MemoryEntry.builder()
            .tenantId(tenantId).userId(userB)
            .type(MemoryEntry.MemoryType.PREFERENCE)
            .content("User B prefers detailed technical explanations")
            .category("response_style")
            .build());

        // When: search with userA's userId
        List<MemoryEntry> resultsA = memoryStore.searchMemories(tenantId, userA, "response style", 10);

        // Then: only user A's memory is returned
        assertEquals(1, resultsA.size());
        assertTrue(resultsA.get(0).getContent().contains("User A"));
        assertFalse(resultsA.get(0).getContent().contains("User B"));

        // When: search with userB's userId
        List<MemoryEntry> resultsB = memoryStore.searchMemories(tenantId, userB, "response style", 10);

        // Then: only user B's memory is returned
        assertEquals(1, resultsB.size());
        assertTrue(resultsB.get(0).getContent().contains("User B"));
    }

    @Test
    void shouldIsolateSessionMessagesBySession() {
        // Session messages are scoped by sessionId
        String session1 = "session-1";
        String session2 = "session-2";

        memoryStore.appendSessionMessage(tenantId, session1, "user", "Hello from user A");
        memoryStore.appendSessionMessage(tenantId, session2, "user", "Hello from user B");

        var stats1 = memoryStore.getSessionStats(tenantId, session1);
        var stats2 = memoryStore.getSessionStats(tenantId, session2);

        assertEquals(1, stats1.fullCount());
        assertEquals(1, stats2.fullCount());

        // Sessions don't leak between each other
        var recall1 = memoryStore.recallSession(tenantId, session1, "", 10,
            com.nousresearch.hermes.memory.store.DecayPolicy.standard());
        assertEquals(1, recall1.size());
        assertTrue(recall1.get(0).content().contains("user A"));
    }

    @Test
    void shouldNotCrossContaminateUsersInLongTermMemory() {
        // Given: memories for two users in the same tenant
        memoryStore.addMemory(MemoryEntry.builder()
            .tenantId(tenantId).userId(userA)
            .content("User A likes Python")
            .type(MemoryEntry.MemoryType.PREFERENCE)
            .build());

        memoryStore.addMemory(MemoryEntry.builder()
            .tenantId(tenantId).userId(userB)
            .content("User B likes Java")
            .type(MemoryEntry.MemoryType.PREFERENCE)
            .build());

        // When: search for "programming language" with each userId
        List<MemoryEntry> userAResults = memoryStore.searchMemories(tenantId, userA, "programming", 5);
        List<MemoryEntry> userBResults = memoryStore.searchMemories(tenantId, userB, "programming", 5);

        // Then: each user only sees their own memories
        assertTrue(userAResults.stream().anyMatch(m -> m.getContent().contains("Python")));
        assertFalse(userAResults.stream().anyMatch(m -> m.getContent().contains("Java")));

        assertTrue(userBResults.stream().anyMatch(m -> m.getContent().contains("Java")));
        assertFalse(userBResults.stream().anyMatch(m -> m.getContent().contains("Python")));
    }

    @Test
    void shouldSetUserIdOnAgent() {
        // Verify TenantAwareAIAgent has setUserId/getCurrentUserId
        var agent = TenantAwareAIAgent.createDefault(new HermesConfig());

        assertNull(agent.getCurrentUserId(), "Default userId should be null");

        agent.setUserId("test-user-123");
        assertEquals("test-user-123", agent.getCurrentUserId());

        agent.setUserId(null);
        assertNull(agent.getCurrentUserId());
    }

    @Test
    void shouldHandleNullUserIdGracefully() {
        // When userId is null (backward compat), search should still work
        memoryStore.addMemory(MemoryEntry.builder()
            .tenantId(tenantId).userId(null)
            .content("Tenant-level fact")
            .build());

        List<MemoryEntry> results = memoryStore.searchMemories(tenantId, null, "fact", 10);
        assertNotNull(results);
        // Should find tenant-level (null userId) memories
        assertFalse(results.isEmpty());
    }
}
