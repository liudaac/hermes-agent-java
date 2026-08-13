package com.nousresearch.hermes.harness.session;

/**
 * Types of events that can be appended to a {@link SessionLog}.
 *
 * <p>Only {@link #USER_MESSAGE}, {@link #ASSISTANT_MESSAGE}, and
 * {@link #TOOL_RESULT} are "surface" events - they produce messages
 * visible in model history. All others are trace, boundary, or audit.</p>
 */
public enum SessionEventType {
    // Surface events (produce model-visible messages)
    USER_MESSAGE,
    ASSISTANT_MESSAGE,
    TOOL_RESULT,

    // Trace events (not visible in model history)
    TOOL_CALL,
    REQUEST_HEADER,
    REQUEST_CONTEXT,

    // Boundary events
    TURN_START,
    TURN_END,
    STEP_START,
    STEP_END,

    // Audit events
    APPROVAL_ASKED,
    APPROVAL_DECIDED,

    // Compaction
    COMPACTION_START,

    // Other (extensible)
    CUSTOM;

    /** Whether this event type produces a model-visible message. */
    public boolean isSurface() {
        return this == USER_MESSAGE || this == ASSISTANT_MESSAGE || this == TOOL_RESULT;
    }

    /** Whether this is a turn/step boundary event. */
    public boolean isBoundary() {
        return this == TURN_START || this == TURN_END
            || this == STEP_START || this == STEP_END;
    }
}
