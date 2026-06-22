package com.nousresearch.hermes.collaboration.pattern;

/**
 * Multi-agent collaboration patterns for business scenarios.
 *
 * <p>Each pattern defines how subtasks are distributed and coordinated among
 * team members. Patterns can be specified per-scenario and override the
 * default sequential execution.</p>
 */
public enum CollaborationPattern {
    /**
     * Sequential chain: A → B → C. Each step waits for the previous.
     * Default fallback when no pattern is specified.
     */
    SEQUENTIAL,

    /**
     * Parallel fan-out: all subtasks execute simultaneously.
     * Results are merged when all complete.
     */
    PARALLEL,

    /**
     * Review mode: Agent A generates output → Agent B reviews → human confirms.
     * Quality gate for high-stakes tasks.
     */
    REVIEW,

    /**
     * Competitive mode: N agents solve the same task independently.
     * Best result is selected by a judge agent or scoring function.
     */
    COMPETITIVE,

    /**
     * Master-Worker: Lead agent decomposes task, workers execute in parallel,
     * lead aggregates results.
     */
    MASTER_WORKER,

    /**
     * Pipeline: data flows through a chain of agents, each transforming output.
     * Like sequential but with explicit data handoff semantics.
     */
    PIPELINE
}
