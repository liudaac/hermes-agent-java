package com.nousresearch.hermes.harness;

/**
 * Error category for agent loop exceptions.
 *
 * <p>Each category maps to a specific recovery strategy:</p>
 * <ul>
 *   <li>{@link #TRANSIENT} - retry with exponential backoff</li>
 *   <li>{@link #LLM_RECOVERABLE} - feed back as tool message for model self-correction</li>
 *   <li>{@link #USER_FIXABLE} - structured pause with recovery suggestion</li>
 *   <li>{@link #FATAL} - log, emit ERROR event, and break</li>
 * </ul>
 */
public enum ErrorCategory {

    /** Network timeout, rate limit (429), server error (5xx) - worth retrying. */
    TRANSIENT,

    /** Malformed model output, bad tool call params - model can self-correct. */
    LLM_RECOVERABLE,

    /** Permission denied, file not found, quota exceeded - user needs to act. */
    USER_FIXABLE,

    /** Unexpected errors that can't be recovered within the loop. */
    FATAL;

    public boolean isRetryable() {
        return this == TRANSIENT;
    }

    public boolean shouldContinueLoop() {
        return this == TRANSIENT || this == LLM_RECOVERABLE;
    }
}
