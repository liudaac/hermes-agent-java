package com.nousresearch.hermes.harness.loop;

/**
 * Target queue for an inbox message.
 */
public enum InboxTarget {
    /** Queued for the next turn (followup) */
    NEXT_TURN,
    /** Injected into the current turn's next step (steer) */
    NEXT_STEP,
    /** Silently inserted without waking the agent (inject) */
    INJECT
}
