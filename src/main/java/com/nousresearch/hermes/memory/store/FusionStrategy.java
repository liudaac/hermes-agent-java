package com.nousresearch.hermes.memory.store;

/**
 * Fusion strategy for long-term memory retrieval.
 */
public enum FusionStrategy {
    /** Reciprocal Rank Fusion (default). No weight tuning needed. */
    RRF,
    /** Weighted sum of normalised scores. Requires {@link RetrievalConfig} weights. */
    WEIGHTED
}
