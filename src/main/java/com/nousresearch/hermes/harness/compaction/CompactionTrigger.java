package com.nousresearch.hermes.harness.compaction;

/**
 * Why automatic compaction is being considered.
 */
public enum CompactionTrigger {
    /** Pre-step check: token usage approaching context window limit. */
    PRESSURE,
    /** LLM returned a context-window-exceeded error; compaction needed to retry. */
    CONTEXT_OVERFLOW
}
