package com.nousresearch.hermes.harness.session;

import java.util.Map;

/**
 * One immutable event in the session log.
 *
 * <p>Events are append-only: once appended, they are never modified.
 * The {@link #seq} is a monotonic counter starting from 1.</p>
 *
 * <p>Surface events ({@link SessionEventType#isSurface}) carry a
 * {@link SurfaceOp} that determines how they participate in the
 * model-visible message history.</p>
 *
 * @param seq        monotonic sequence number
 * @param timestamp  epoch millis
 * @param type       event type
 * @param data       payload (role, content, toolCallId, etc.)
 * @param surfaceOp  surface operation (null for non-surface events)
 */
public record SessionEvent(
    long seq,
    long timestamp,
    SessionEventType type,
    Map<String, Object> data,
    SurfaceOp surfaceOp
) {
    /**
     * Create a surface event with APPEND op.
     */
    public static SessionEvent surface(long seq, long timestamp, SessionEventType type, Map<String, Object> data) {
        return new SessionEvent(seq, timestamp, type, data, SurfaceOp.APPEND);
    }

    /**
     * Create a surface event with REPLACE op.
     */
    public static SessionEvent replace(long seq, long timestamp, SessionEventType type,
                                        Map<String, Object> data, long start, long end) {
        return new SessionEvent(seq, timestamp, type, data, SurfaceOp.replace(start, end));
    }

    /**
     * Create a non-surface (trace/boundary/audit) event.
     */
    public static SessionEvent trace(long seq, long timestamp, SessionEventType type, Map<String, Object> data) {
        return new SessionEvent(seq, timestamp, type, data, null);
    }

    /** Whether this is a surface event. */
    public boolean isSurface() {
        return type.isSurface();
    }
}
