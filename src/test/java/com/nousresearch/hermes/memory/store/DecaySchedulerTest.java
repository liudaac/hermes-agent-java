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

}
