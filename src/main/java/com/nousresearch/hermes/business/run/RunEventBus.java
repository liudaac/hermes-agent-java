package com.nousresearch.hermes.business.run;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Lightweight event bus for business run lifecycle events.
 *
 * <p>Publishes events as a run progresses through its lifecycle. SSE endpoints
 * and other observers can subscribe to receive real-time updates.</p>
 *
 * <p>Events are fire-and-forget; subscribers should not block the publisher
 * thread for long periods.</p>
 */
public class RunEventBus {

    private static final Logger logger = LoggerFactory.getLogger(RunEventBus.class);

    /** Global subscribers (all runs) — runId -> event */
    private final List<Consumer<RunEvent>> globalSubscribers = new CopyOnWriteArrayList<>();

    /** Per-run subscribers */
    private final Map<String, List<Consumer<RunEvent>>> runSubscribers = new ConcurrentHashMap<>();

    /**
     * Publish an event to all subscribers (global + per-run).
     */
    public void publish(RunEvent event) {
        logger.debug("Publishing run event: {}/{}", event.runId(), event.type());

        // Global subscribers
        for (var sub : globalSubscribers) {
            try {
                sub.accept(event);
            } catch (Exception e) {
                logger.warn("Global subscriber failed for event {}: {}", event.type(), e.getMessage());
            }
        }

        // Per-run subscribers
        var runSubs = runSubscribers.get(event.runId());
        if (runSubs != null) {
            for (var sub : runSubs) {
                try {
                    sub.accept(event);
                } catch (Exception e) {
                    logger.warn("Run subscriber failed for {}/{}: {}", event.runId(), event.type(), e.getMessage());
                }
            }
        }

        // Clean up terminal events after a delay
        if (event.type().isTerminal()) {
            // Keep subscribers for a grace period then clean up
            scheduleCleanup(event.runId());
        }
    }

    /**
     * Subscribe to all run events.
     */
    public void subscribeGlobal(Consumer<RunEvent> subscriber) {
        globalSubscribers.add(subscriber);
    }

    /**
     * Subscribe to events for a specific run.
     */
    public void subscribe(String runId, Consumer<RunEvent> subscriber) {
        runSubscribers.computeIfAbsent(runId, k -> new CopyOnWriteArrayList<>()).add(subscriber);
    }

    /**
     * Remove a per-run subscriber.
     */
    public void unsubscribe(String runId, Consumer<RunEvent> subscriber) {
        var subs = runSubscribers.get(runId);
        if (subs != null) {
            subs.remove(subscriber);
            if (subs.isEmpty()) {
                runSubscribers.remove(runId);
            }
        }
    }

    private void scheduleCleanup(String runId) {
        // Clean up after 60 seconds to give slow clients time to receive terminal events
        new Thread(() -> {
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            runSubscribers.remove(runId);
            logger.debug("Cleaned up event subscribers for run {}", runId);
        }, "run-event-cleanup-" + runId.substring(0, Math.min(10, runId.length()))).start();
    }

    /**
     * Event types for business run lifecycle.
     */
    public enum EventType {
        RUN_STARTED(false),
        STEP_STARTED(false),
        STEP_UPDATED(false),
        STEP_COMPLETED(false),
        STEP_FAILED(false),
        RUN_COMPLETED(true),
        RUN_FAILED(true),
        RUN_NEEDS_APPROVAL(true);

        private final boolean terminal;

        EventType(boolean terminal) {
            this.terminal = terminal;
        }

        public boolean isTerminal() { return terminal; }
    }

    /**
     * A single run event.
     */
    public record RunEvent(
        String runId,
        String workspaceId,
        EventType type,
        String message,
        Map<String, Object> data,
        long timestamp
    ) {
        public RunEvent(String runId, String workspaceId, EventType type, String message, Map<String, Object> data) {
            this(runId, workspaceId, type, message, data, System.currentTimeMillis());
        }
    }
}
