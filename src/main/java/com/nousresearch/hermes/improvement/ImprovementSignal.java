package com.nousresearch.hermes.improvement;

/**
 * An improvement signal collected from user behavior.
 *
 * <p>Signals are the raw input to the self-improvement engine.
 * Multiple signals of the same type accumulate to build confidence
 * for preference learning and pattern evolution.</p>
 *
 * @param id        unique signal ID
 * @param tenantId  tenant
 * @param userId    user (nullable for tenant-level signals)
 * @param type      signal type
 * @param sessionId related session ID (nullable)
 * @param content   human-readable description of the signal
 * @param weight    signal weight 0.0-1.0
 * @param timestamp epoch millis
 * @param processed whether the signal has been consumed by the improvement engine
 */
public record ImprovementSignal(
        String id,
        String tenantId,
        String userId,
        SignalType type,
        String sessionId,
        String content,
        double weight,
        long timestamp,
        boolean processed
) {
    /**
     * Creates a new unprocessed signal with a generated ID and current timestamp.
     */
    public static ImprovementSignal create(String tenantId, String userId,
                                            SignalType type, String sessionId,
                                            String content, double weight) {
        return new ImprovementSignal(
                "sig_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16),
                tenantId, userId, type, sessionId, content, weight,
                System.currentTimeMillis(), false
        );
    }

    /**
     * Returns a copy with processed=true.
     */
    public ImprovementSignal markProcessed() {
        return new ImprovementSignal(id, tenantId, userId, type, sessionId,
                content, weight, timestamp, true);
    }
}
