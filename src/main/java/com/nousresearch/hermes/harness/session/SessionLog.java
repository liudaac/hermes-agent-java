package com.nousresearch.hermes.harness.session;

import com.nousresearch.hermes.model.ModelMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Append-only event log for one agent session.
 *
 * <p>All agent activity (user messages, assistant messages, tool calls/results,
 * turn/step boundaries, request headers, approvals, compaction) is recorded
 * as {@link SessionEvent}s. The log is the single source of truth; the
 * model-visible message history is derived from it via
 * {@link #deriveMessages()}.</p>
 *
 * <p>This replaces the old {@code List<ModelMessage> conversationHistory}
 * approach. During migration, both can coexist (dual-write).</p>
 */
public class SessionLog {
    private static final Logger logger = LoggerFactory.getLogger(SessionLog.class);

    private final ConcurrentLinkedQueue<SessionEvent> events = new ConcurrentLinkedQueue<>();
    private final AtomicLong seqCounter = new AtomicLong(0);

    /**
     * Append a surface event (USER_MESSAGE, ASSISTANT_MESSAGE, TOOL_RESULT)
     * with APPEND op.
     */
    public SessionEvent appendSurface(SessionEventType type, Map<String, Object> data) {
        if (!type.isSurface()) {
            throw new IllegalArgumentException("appendSurface requires a surface event type, got " + type);
        }
        SessionEvent event = SessionEvent.surface(
            seqCounter.incrementAndGet(),
            System.currentTimeMillis(),
            type,
            Map.copyOf(data)
        );
        events.add(event);
        return event;
    }

    /**
     * Append a surface event with REPLACE op (for compaction).
     */
    public SessionEvent appendReplace(SessionEventType type, Map<String, Object> data,
                                       long startSeq, long endSeq) {
        if (!type.isSurface()) {
            throw new IllegalArgumentException("appendReplace requires a surface event type, got " + type);
        }
        SessionEvent event = SessionEvent.replace(
            seqCounter.incrementAndGet(),
            System.currentTimeMillis(),
            type, data,
            startSeq, endSeq
        );
        events.add(event);
        logger.debug("Appended REPLACE event: seq={}, range=[{}-{}]", event.seq(), startSeq, endSeq);
        return event;
    }

    /**
     * Append a non-surface (trace/boundary/audit) event.
     */
    public SessionEvent appendTrace(SessionEventType type, Map<String, Object> data) {
        // Guard against null values in the map (Map.copyOf rejects them)
        Map<String, Object> safeData;
        if (data == null || data.isEmpty()) {
            safeData = Map.of();
        } else {
            safeData = new java.util.HashMap<>();
            for (var entry : data.entrySet()) {
                if (entry.getValue() != null) {
                    safeData.put(entry.getKey(), entry.getValue());
                }
            }
            safeData = Map.copyOf(safeData);
        }
        SessionEvent event = SessionEvent.trace(
            seqCounter.incrementAndGet(),
            System.currentTimeMillis(),
            type,
            safeData
        );
        events.add(event);
        return event;
    }

    /**
     * Derive the model-visible message history from the event log.
     *
     * <p>This folds all surface events, applying REPLACE ops, to produce
     * the exact {@link ModelMessage} list the model should see.</p>
     *
     * @return unmodifiable list of model messages
     */
    public List<ModelMessage> deriveMessages() {
        return SessionSurfaceFolder.fold(new ArrayList<>(events));
    }

    /**
     * Get all events (for replay, audit, fork).
     */
    public List<SessionEvent> allEvents() {
        return new ArrayList<>(events);
    }

    /**
     * Get the last sequence number used.
     */
    public long lastSeq() {
        return seqCounter.get();
    }

    /**
     * Number of events in the log.
     */
    public int size() {
        return events.size();
    }

    /**
     * Whether the log is empty.
     */
    public boolean isEmpty() {
        return events.isEmpty();
    }

    /**
     * Find the last event of a given type.
     */
    public Optional<SessionEvent> findLast(SessionEventType type) {
        SessionEvent found = null;
        for (SessionEvent e : events) {
            if (e.type() == type) found = e;
        }
        return Optional.ofNullable(found);
    }

    /**
     * Find events of a given type (in order).
     */
    public List<SessionEvent> findByType(SessionEventType type) {
        List<SessionEvent> result = new ArrayList<>();
        for (SessionEvent e : events) {
            if (e.type() == type) result.add(e);
        }
        return result;
    }
}
