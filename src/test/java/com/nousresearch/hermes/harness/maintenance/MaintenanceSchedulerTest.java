package com.nousresearch.hermes.harness.maintenance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class MaintenanceSchedulerTest {

    private MaintenanceScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new MaintenanceScheduler();
    }

    @Test
    void testEmptyScheduler() {
        assertTrue(scheduler.runAll());
        assertTrue(scheduler.jobs().isEmpty());
    }

    @Test
    void testSingleJobRuns() {
        AtomicBoolean ran = new AtomicBoolean(false);
        scheduler.register(new TestJob("job1", 10, () -> ran.set(true)));

        assertTrue(scheduler.runAll());
        assertTrue(ran.get());
    }

    @Test
    void testMultipleJobsInPriorityOrder() {
        List<String> executionOrder = new ArrayList<>();
        scheduler.register(new TestJob("job-c", 30, () -> executionOrder.add("job-c")));
        scheduler.register(new TestJob("job-a", 10, () -> executionOrder.add("job-a")));
        scheduler.register(new TestJob("job-b", 20, () -> executionOrder.add("job-b")));

        assertTrue(scheduler.runAll());
        assertEquals(3, executionOrder.size());
        assertEquals("job-a", executionOrder.get(0));
        assertEquals("job-b", executionOrder.get(1));
        assertEquals("job-c", executionOrder.get(2));
    }

    @Test
    void testExceptionInOneJobDoesNotBlockOthers() {
        AtomicBoolean secondRan = new AtomicBoolean(false);
        scheduler.register(new TestJob("failing", 10, () -> {
            throw new RuntimeException("boom");
        }));
        scheduler.register(new TestJob("ok", 20, () -> secondRan.set(true)));

        assertTrue(scheduler.runAll());
        assertTrue(secondRan.get());
    }

    @Test
    void testInterruptStopsSubsequentJobs() throws InterruptedException {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch interruptLatch = new CountDownLatch(1);
        AtomicBoolean secondRan = new AtomicBoolean(false);

        scheduler.register(new TestJob("first", 10, () -> {
            firstStarted.countDown();
            try {
                interruptLatch.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        scheduler.register(new TestJob("second", 20, () -> secondRan.set(true)));

        // Run in a separate thread so we can interrupt
        var thread = Thread.startVirtualThread(() -> scheduler.runAll());

        // Wait for first job to start, then interrupt
        assertTrue(firstStarted.await(2, TimeUnit.SECONDS));
        scheduler.interrupt();
        interruptLatch.countDown();

        // Wait for completion
        thread.join(5000);

        assertFalse(secondRan.get(), "Second job should not have run after interrupt");
    }

    @Test
    void testIsRunningDuringExecution() {
        AtomicBoolean wasRunning = new AtomicBoolean(false);
        scheduler.register(new TestJob("job1", 10, () -> {
            wasRunning.set(scheduler.isRunning());
        }));

        scheduler.runAll();
        assertTrue(wasRunning.get());
        assertFalse(scheduler.isRunning());
    }

    @Test
    void testUnregisterRemovesJob() {
        AtomicBoolean ran = new AtomicBoolean(false);
        var job = new TestJob("removable", 10, () -> ran.set(true));
        scheduler.register(job);

        assertTrue(scheduler.unregister("removable"));
        assertFalse(scheduler.unregister("nonexistent"));

        scheduler.runAll();
        assertFalse(ran.get());
    }

    @Test
    void testClearRemovesAllJobs() {
        scheduler.register(new TestJob("job1", 10, () -> {}));
        scheduler.register(new TestJob("job2", 20, () -> {}));
        assertEquals(2, scheduler.jobs().size());

        scheduler.clear();
        assertTrue(scheduler.jobs().isEmpty());
    }

    @Test
    void testRunAllWhenAlreadyRunningReturnsFalse() throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(1);
        AtomicInteger concurrentRuns = new AtomicInteger(0);

        scheduler.register(new TestJob("blocking", 10, () -> {
            int runs = concurrentRuns.incrementAndGet();
            started.countDown();
            try {
                finishLatch.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));

        var t1 = Thread.startVirtualThread(() -> scheduler.runAll());
        assertTrue(started.await(2, TimeUnit.SECONDS));

        // Second call should return false (already running)
        // Use a separate thread to avoid blocking
        var t2 = Thread.startVirtualThread(() -> scheduler.runAll());

        finishLatch.countDown();
        t1.join(3000);
        t2.join(3000);

        assertEquals(1, concurrentRuns.get());
    }

    /** Simple test job implementation */
    private static class TestJob implements MaintenanceJob {
        private final String name;
        private final int priority;
        private final Runnable action;

        TestJob(String name, int priority, Runnable action) {
            this.name = name;
            this.priority = priority;
            this.action = action;
        }

        @Override
        public String name() { return name; }

        @Override
        public int priority() { return priority; }

        @Override
        public void run() { action.run(); }
    }
}
