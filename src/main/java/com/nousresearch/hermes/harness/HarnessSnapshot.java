package com.nousresearch.hermes.harness;

/**
 * Read-only snapshot of a harness's current state.
 * Used by /api/harness/active and SSE streams.
 */
public record HarnessSnapshot(
    String sessionId,
    String agentId,
    String tenantId,
    String lifecycle,       // IDLE | RUNNING | PAUSED_APPROVAL | PAUSED_GOVERNANCE | STOPPED | FAILED
    int iterationsUsed,
    int iterationsMax,
    int messageCount,
    int toolCallCount,
    long startedAtMs,
    long lastActivityMs,
    String currentPhase     // thinking | acting | observing | idle | approval_pending
) {
    public static HarnessSnapshot from(String sessionId, String agentId, String tenantId,
                                        LoopState state, long startedAtMs) {
        return new HarnessSnapshot(
            sessionId, agentId, tenantId,
            state.lifecycle().name(),
            state.iterationsUsed(),
            state.iterationsUsed() + state.iterationsRemaining(),
            state.historySize(),
            0, // toolCallCount tracked by EventEmitter subscribers
            startedAtMs,
            System.currentTimeMillis(),
            phaseFromState(state)
        );
    }

    private static String phaseFromState(LoopState state) {
        return switch (state.lifecycle()) {
            case RUNNING -> "thinking";
            case PAUSED_APPROVAL -> "approval_pending";
            case PAUSED_GOVERNANCE -> "idle";
            case STOPPED -> "idle";
            case FAILED -> "idle";
            case IDLE -> "idle";
        };
    }
}
