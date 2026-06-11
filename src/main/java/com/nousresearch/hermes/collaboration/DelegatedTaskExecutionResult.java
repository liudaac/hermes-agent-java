package com.nousresearch.hermes.collaboration;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Outcome returned by a delegated-task executor adapter.
 *
 * <p>An execution result is distinct from {@link DelegatedTaskResult}: it can
 * represent a safe refusal / not-executed state before any specialist result is
 * submitted to parent verification.</p>
 */
public record DelegatedTaskExecutionResult(
    String executorName,
    boolean executed,
    boolean submitted,
    String status,
    String message,
    DelegatedTaskResult delegatedTaskResult,
    ParentVerificationResult verificationResult,
    DelegatedTaskExecutionPolicy policy,
    Instant completedAt
) {
    public DelegatedTaskExecutionResult {
        completedAt = completedAt != null ? completedAt : Instant.now();
    }

    public static DelegatedTaskExecutionResult notExecuted(
        String executorName,
        String status,
        String message,
        DelegatedTaskExecutionPolicy policy
    ) {
        return new DelegatedTaskExecutionResult(
            executorName,
            false,
            false,
            status != null ? status : "NOT_EXECUTED",
            message,
            null,
            null,
            policy,
            Instant.now()
        );
    }

    public static DelegatedTaskExecutionResult executed(
        String executorName,
        String message,
        DelegatedTaskResult result,
        DelegatedTaskExecutionPolicy policy
    ) {
        return new DelegatedTaskExecutionResult(
            executorName,
            true,
            false,
            "EXECUTED",
            message,
            result,
            null,
            policy,
            Instant.now()
        );
    }

    public DelegatedTaskExecutionResult withSubmission(ParentVerificationResult verification) {
        return new DelegatedTaskExecutionResult(
            executorName,
            executed,
            verification != null,
            verification != null ? verification.status() : status,
            message,
            delegatedTaskResult,
            verification,
            policy,
            completedAt
        );
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("executor_name", executorName);
        m.put("executed", executed);
        m.put("submitted", submitted);
        m.put("status", status);
        m.put("message", message);
        m.put("delegated_task_result", delegatedTaskResult != null ? delegatedTaskResult.toMap() : null);
        m.put("verification_result", verificationResult != null ? verificationResult.toMap() : null);
        m.put("policy", policy != null ? policy.toMap() : null);
        m.put("completed_at", completedAt.toString());
        return m;
    }
}
