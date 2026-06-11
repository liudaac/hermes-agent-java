package com.nousresearch.hermes.collaboration;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Inert lifecycle model for an advisory delegated task.
 *
 * <p>Instances move PENDING -> SUBMITTED -> ACCEPTED/REJECTED. They model
 * specialist execution and parent verification, but never launch external
 * subprocesses or subagents.</p>
 */
public class DelegatedTask {
    public enum Status { PENDING, SUBMITTED, ACCEPTED, REJECTED }

    private final String taskId;
    private final DelegatedTaskEnvelope envelope;
    private final ParentVerificationPolicy verificationPolicy;
    private final Instant createdAt;
    private volatile Status status;
    private volatile DelegatedTaskResult result;
    private volatile ParentVerificationResult verification;

    public DelegatedTask(String taskId, DelegatedTaskEnvelope envelope, ParentVerificationPolicy verificationPolicy) {
        this(taskId, envelope, verificationPolicy, Instant.now(), Status.PENDING, null, null);
    }

    public DelegatedTask(
        String taskId,
        DelegatedTaskEnvelope envelope,
        ParentVerificationPolicy verificationPolicy,
        Instant createdAt,
        Status status,
        DelegatedTaskResult result,
        ParentVerificationResult verification
    ) {
        if (taskId == null || taskId.isBlank()) throw new IllegalArgumentException("taskId is required");
        if (envelope == null) throw new IllegalArgumentException("delegated task envelope is required");
        this.taskId = taskId;
        this.envelope = envelope;
        this.verificationPolicy = verificationPolicy != null ? verificationPolicy : ParentVerificationPolicy.strict();
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        this.status = status != null ? status : Status.PENDING;
        this.result = result;
        this.verification = verification;
    }

    public synchronized ParentVerificationResult submitResult(DelegatedTaskResult result) {
        if (status == Status.ACCEPTED || status == Status.REJECTED) {
            throw new IllegalStateException("delegated task already finalized: " + status);
        }
        this.result = result;
        this.status = Status.SUBMITTED;
        ParentVerificationResult checked = verificationPolicy.verify(this, result);
        this.verification = checked;
        this.status = checked.accepted() ? Status.ACCEPTED : Status.REJECTED;
        return checked;
    }

    public boolean isTerminal() {
        return status == Status.ACCEPTED || status == Status.REJECTED;
    }

    public String taskId() { return taskId; }
    public DelegatedTaskEnvelope envelope() { return envelope; }
    public ParentVerificationPolicy verificationPolicy() { return verificationPolicy; }
    public Instant createdAt() { return createdAt; }
    public Status status() { return status; }
    public DelegatedTaskResult result() { return result; }
    public ParentVerificationResult verification() { return verification; }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("task_id", taskId);
        m.put("status", status.name());
        m.put("created_at", createdAt.toString());
        m.put("envelope", envelope.toMap());
        m.put("verification_policy", verificationPolicy.toMap());
        m.put("result", result != null ? result.toMap() : null);
        m.put("verification", verification != null ? verification.toMap() : null);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static DelegatedTask fromMap(Map<String, Object> m) {
        if (m == null) throw new IllegalArgumentException("map is required");
        DelegatedTaskEnvelope envelope = envelopeFromMap((Map<String, Object>) m.get("envelope"));
        Status status = Status.PENDING;
        try { status = Status.valueOf(String.valueOf(m.getOrDefault("status", "PENDING"))); }
        catch (Exception ignored) {}
        return new DelegatedTask(
            String.valueOf(m.get("task_id")),
            envelope,
            ParentVerificationPolicy.fromMap((Map<String, Object>) m.get("verification_policy")),
            parseInstant(m.get("created_at")),
            status,
            DelegatedTaskResult.fromMap((Map<String, Object>) m.get("result")),
            ParentVerificationResult.fromMap((Map<String, Object>) m.get("verification"))
        );
    }

    private static DelegatedTaskEnvelope envelopeFromMap(Map<String, Object> m) {
        if (m == null) throw new IllegalArgumentException("envelope map is required");
        return new DelegatedTaskEnvelope(
            stringOrNull(m.get("intent")),
            stringOrNull(m.get("run_id")),
            stringOrNull(m.get("suggested_team_id")),
            stringOrNull(m.get("suggested_profile")),
            stringOrNull(m.get("reason")),
            m.get("context_pressure") instanceof Map<?, ?> cp ? new LinkedHashMap<>((Map<String, Object>) cp) : Map.of(),
            parseInstant(m.get("created_at"))
        );
    }

    private static Instant parseInstant(Object value) {
        if (value == null) return Instant.now();
        try { return Instant.parse(String.valueOf(value)); }
        catch (Exception ignored) { return Instant.now(); }
    }

    private static String stringOrNull(Object value) {
        if (value == null) return null;
        String s = String.valueOf(value);
        return "null".equals(s) ? null : s;
    }
}
