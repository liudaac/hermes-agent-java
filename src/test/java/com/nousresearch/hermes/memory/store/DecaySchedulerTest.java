package com.nousresearch.hermes.memory.store;

import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link DecayScheduler}.
 */
class DecaySchedulerTest {

    private LocalMemoryStore store;
    private DecayScheduler scheduler;

    @BeforeEach
    void setUp() {
        store = new LocalMemoryStore();
        scheduler = new DecayScheduler(store);
    }

    @AfterEach
    void tearDown() {
        scheduler.stop();
    }

    @Test
    @DisplayName("Register and unregister sessions")
    void testSessionRegistration() {
        scheduler.registerSession("t1", "s1", DecayPolicy.standard());
        assertEquals(1, scheduler.getActiveSessionCount());

        scheduler.registerSession("t1", "s2", DecayPolicy.aggressive());
        assertEquals(2, scheduler.getActiveSessionCount());

        scheduler.unregisterSession("t1", "s1");
        assertEquals(1, scheduler.getActiveSessionCount());

        scheduler.unregisterSession("t1", "s2");
        assertEquals(0, scheduler.getActiveSessionCount());
    }

    @Test
    @DisplayName("Registered sessions appear in snapshot")
    void testGetRegisteredSessions() {
        scheduler.registerSession("t1", "s1", DecayPolicy.standard());

        var sessions = scheduler.getRegisteredSessions();
        assertEquals(1, sessions.size());
        assertEquals("t1", sessions.get(0).get("tenantId"));
        assertEquals("s1", sessions.get(0).get("sessionId"));
    }

    @Test
    @DisplayName("Start and stop scheduler lifecycle")
    void testLifecycle() {
        scheduler.start();
        scheduler.start();  // Idempotent

        scheduler.registerSession("t1", "s1", DecayPolicy.standard());
        assertEquals(1, scheduler.getActiveSessionCount());

        scheduler.stop();
        assertEquals(0, scheduler.getActiveSessionCount());
    }

    @Test
    @DisplayName("Decay scheduler handles empty sessions gracefully")
    void testEmptySessionsNoOp() {
        scheduler.start();
        assertEquals(0, scheduler.getActiveSessionCount());
    }

    @Test
    @DisplayName("Custom summariser and fact extractor work end-to-end")
    void testCustomLLMFunctions() {
        DecayPolicy fast = DecayPolicy.builder()
            .fullWindow(Duration.ofMillis(1))
            .warmWindow(Duration.ofMillis(2))
            .coolWindow(Duration.ofMillis(3))
            .summaryBatchSize(1)
            .build();

        store.appendSessionMessage("t1", "s1", "user", "Important decision: use Kafka");
        try { Thread.sleep(5); } catch (InterruptedException ignored) {}

        // First decay: compress to COOL with custom summariser
        store.runDecayCycle("t1", "s1", fast,
            msgs -> "Custom summary of " + msgs.size() + " messages",
            (summary, maxFacts) -> List.of()
        );

        // Second decay: evict with custom fact extractor
        try { Thread.sleep(5); } catch (InterruptedException ignored) {}

        store.runDecayCycle("t1", "s1", fast,
            msgs -> "unused",
            (summary, maxFacts) -> List.of("Custom fact: " + summary.substring(0, Math.min(30, summary.length())))
        );

        // Verify custom fact is in long-term memory
        List<MemoryEntry> results = store.searchMemories("t1", null, "Custom", 10);
        assertFalse(results.isEmpty());
        assertTrue(results.stream().anyMatch(m -> m.getContent().contains("Custom")));
    }
}
