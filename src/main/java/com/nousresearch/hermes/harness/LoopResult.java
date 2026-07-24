package com.nousresearch.hermes.harness;

/**
 * Result of an agent loop execution.
 *
 * <p>Either {@link #completed} (with a text response), {@link #paused}
 * (waiting for approval), or {@link #failed} (with an error message).</p>
 */
public sealed interface LoopResult permits LoopResult.Completed, LoopResult.Paused, LoopResult.Failed {

    /** Loop finished normally with a text response. */
    record Completed(String response) implements LoopResult {}

    /** Loop paused waiting for tool approval. Checkpoint is in {@link LoopState}. */
    record Paused(LoopState state) implements LoopResult {}

    /** Loop failed with an error. */
    record Failed(String error) implements LoopResult {}

    /** Convenience: was the loop completed? */
    default boolean isCompleted() { return this instanceof Completed; }

    /** Convenience: was the loop paused? */
    default boolean isPaused() { return this instanceof Paused; }

    /** Convenience: was the loop failed? */
    default boolean isFailed() { return this instanceof Failed; }
}
