package com.nousresearch.hermes.memory.store;

/**
 * Decay stage for a short-term memory item.
 */
public enum RecallStage {
    /** Fresh: full weight, original content. */
    FULL,
    /** Aging: reduced weight, original content still kept. */
    WARM,
    /** Old: low weight, content replaced by LLM summary. */
    COOL,
    /** Expired: removed from short-term, key facts promoted to long-term. */
    EVICT
}
