package com.nousresearch.hermes.collaboration;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Serializable advisory envelope for a task that should be delegated.
 * It is intentionally inert: callers may inspect/log it, but Hermes does not
 * spawn an external subprocess from this foundation.
 */
public record DelegatedTaskEnvelope(
    String intent,
    String runId,
    String suggestedTeamId,
    String suggestedProfile,
    String reason,
    Map<String, Object> contextPressure,
    Instant createdAt
) {
    public static DelegatedTaskEnvelope of(String intent, String runId, DelegationDecision decision) {
        return new DelegatedTaskEnvelope(
            intent,
            runId,
            decision != null ? decision.suggestedTeamId() : null,
            decision != null ? decision.suggestedProfile() : null,
            decision != null ? decision.reason() : null,
            decision != null && decision.contextPressure() != null ? decision.contextPressure().toMap() : ContextPressureReport.none().toMap(),
            Instant.now()
        );
    }

    public DelegatedTask toPendingTask(String taskId) {
        return new DelegatedTask(taskId, this, ParentVerificationPolicy.strict());
    }

    public DelegatedTask toPendingTask(String taskId, ParentVerificationPolicy policy) {
        return new DelegatedTask(taskId, this, policy != null ? policy : ParentVerificationPolicy.strict());
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("intent", intent);
        m.put("run_id", runId);
        m.put("suggested_team_id", suggestedTeamId);
        m.put("suggested_profile", suggestedProfile);
        m.put("reason", reason);
        m.put("context_pressure", contextPressure);
        m.put("created_at", createdAt.toString());
        return m;
    }
}
