package com.nousresearch.hermes.collaboration;

import java.time.Instant;
import java.util.LinkedHashMap;
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

    @SuppressWarnings("unchecked")
    public static DelegatedTaskExecutionResult fromMap(Map<String, Object> m) {
        if (m == null) return null;
        DelegatedTaskResult taskResult = m.get("delegated_task_result") instanceof Map<?, ?> tr
            ? DelegatedTaskResult.fromMap((Map<String, Object>) tr)
            : null;
        ParentVerificationResult verification = m.get("verification_result") instanceof Map<?, ?> vr
            ? ParentVerificationResult.fromMap((Map<String, Object>) vr)
            : null;
        DelegatedTaskExecutionPolicy policy = null;
        if (m.get("policy") instanceof Map<?, ?> pm) {
            Map<String, Object> policyMap = (Map<String, Object>) pm;
            ParentVerificationPolicy parentPolicy = policyMap.get("parent_verification_policy") instanceof Map<?, ?> pvp
                ? ParentVerificationPolicy.fromMap((Map<String, Object>) pvp)
                : ParentVerificationPolicy.strict();
            policy = new DelegatedTaskExecutionPolicy(
                booleanOrDefault(policyMap.get("allow_external_execution"), false),
                booleanOrDefault(policyMap.get("allow_file_changes"), false),
                booleanOrDefault(policyMap.get("allow_commands"), false),
                java.time.Duration.ofMillis(longOrDefault(policyMap.get("timeout_ms"), 300_000L)),
                listOfStrings(policyMap.get("allowed_changed_file_prefixes")),
                parentPolicy,
                policyMap.get("metadata") instanceof Map<?, ?> meta ? new LinkedHashMap<>((Map<String, Object>) meta) : Map.of()
            );
        }
        Instant completed = Instant.now();
        try { if (m.get("completed_at") != null) completed = Instant.parse(String.valueOf(m.get("completed_at"))); } catch (Exception ignored) {}
        return new DelegatedTaskExecutionResult(
            stringOrNull(m.get("executor_name")),
            booleanOrDefault(m.get("executed"), false),
            booleanOrDefault(m.get("submitted"), false),
            stringOrNull(m.get("status")),
            stringOrNull(m.get("message")),
            taskResult,
            verification,
            policy,
            completed
        );
    }

    private static boolean booleanOrDefault(Object value, boolean fallback) {
        if (value == null) return fallback;
        if (value instanceof Boolean b) return b;
        String s = String.valueOf(value).trim();
        return s.isBlank() ? fallback : ("true".equalsIgnoreCase(s) || "1".equals(s));
    }

    private static long longOrDefault(Object value, long fallback) {
        try { return value instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(value)); }
        catch (Exception ignored) { return fallback; }
    }

    private static java.util.List<String> listOfStrings(Object value) {
        if (!(value instanceof java.util.List<?> list)) return java.util.List.of();
        return list.stream().filter(x -> x != null && !String.valueOf(x).isBlank()).map(String::valueOf).toList();
    }

    private static String stringOrNull(Object value) {
        if (value == null) return null;
        String s = String.valueOf(value);
        return "null".equals(s) ? null : s;
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
