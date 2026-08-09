package com.nousresearch.hermes.improvement;

import java.util.Map;

/**
 * An improvement signal collected from user behavior.
 *
 * <p>Signals are the raw input to the self-improvement engine.
 * Multiple signals of the same type accumulate to build confidence
 * for preference learning and pattern evolution.</p>
 *
 * <p>Signals are routed by {@link SignalScope}: user-level signals
 * drive personal adaptation, space-level signals drive team evolution,
 * org-level signals drive cross-space insights.</p>
 *
 * @param id        unique signal ID
 * @param tenantId  tenant/space ID
 * @param userId    user (nullable for space/org-level signals)
 * @param type      signal type
 * @param scope     routing scope: USER, SPACE, or ORG
 * @param sessionId related session ID (nullable)
 * @param content   human-readable description of the signal
 * @param weight    signal weight 0.0-1.0
 * @param timestamp epoch millis
 * @param processed whether the signal has been consumed by the improvement engine
 * @param metadata  extra key-value data for this signal (e.g. preference_key, tool name)
 */
public record ImprovementSignal(
        String id,
        String tenantId,
        String userId,
        SignalType type,
        SignalScope scope,
        String sessionId,
        String content,
        double weight,
        long timestamp,
        boolean processed,
        Map<String, Object> metadata
) {
    /**
     * Creates a new unprocessed signal with a generated ID and current timestamp.
     */
    public static ImprovementSignal create(String tenantId, String userId,
                                            SignalType type, String sessionId,
                                            String content, double weight) {
        return create(tenantId, userId, type, SignalScope.USER, sessionId, content, weight, Map.of());
    }

    /**
     * Creates a new unprocessed signal with scope and metadata.
     */
    public static ImprovementSignal create(String tenantId, String userId,
                                            SignalType type, SignalScope scope,
                                            String sessionId, String content,
                                            double weight, Map<String, Object> metadata) {
        return new ImprovementSignal(
                "sig_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16),
                tenantId, userId, type, scope, sessionId, content, weight,
                System.currentTimeMillis(), false,
                metadata != null ? metadata : Map.of()
        );
    }

    /**
     * Returns a copy with processed=true.
     */
    public ImprovementSignal markProcessed() {
        return new ImprovementSignal(id, tenantId, userId, type, scope, sessionId,
                content, weight, timestamp, true, metadata);
    }

    /**
     * Returns a copy with a different scope.
     */
    public ImprovementSignal withScope(SignalScope newScope) {
        return new ImprovementSignal(id, tenantId, userId, type, newScope, sessionId,
                content, weight, timestamp, processed, metadata);
    }
}
