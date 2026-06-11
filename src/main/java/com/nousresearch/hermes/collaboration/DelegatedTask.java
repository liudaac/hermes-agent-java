package com.nousresearch.hermes.collaboration;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
    private final List<DelegatedTaskVerificationEntry> verificationHistory;
    private volatile Status status;
    private volatile DelegatedTaskResult result;
    private volatile ParentVerificationResult verification;

    public DelegatedTask(String taskId, DelegatedTaskEnvelope envelope, ParentVerificationPolicy verificationPolicy) {
        this(taskId, envelope, verificationPolicy, Instant.now(), Status.PENDING, null, null, List.of());
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
        this(taskId, envelope, verificationPolicy, createdAt, status, result, verification, List.of());
    }

    public DelegatedTask(
        String taskId,
        DelegatedTaskEnvelope envelope,
        ParentVerificationPolicy verificationPolicy,
        Instant createdAt,
        Status status,
        DelegatedTaskResult result,
        ParentVerificationResult verification,
        List<DelegatedTaskVerificationEntry> verificationHistory
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
        List<DelegatedTaskVerificationEntry> history = verificationHistory != null ? new ArrayList<>(verificationHistory) : new ArrayList<>();
        if (history.isEmpty() && verification != null) {
            history.add(new DelegatedTaskVerificationEntry("verification_1", this.verificationPolicy, verification, verification.verifiedAt()));
        }
        this.verificationHistory = history;
    }

    public synchronized ParentVerificationResult submitResult(DelegatedTaskResult result) {
        this.result = result;
        this.status = Status.SUBMITTED;
        return verifyWithPolicy(verificationPolicy);
    }

    /**
     * Re-run parent-side verification with a supplied policy.
     *
     * <p>This remains an inert simulation: it only updates local lifecycle
     * state and never executes specialist work or external processes.</p>
     */
    public synchronized ParentVerificationResult verifyWithPolicy(ParentVerificationPolicy policy) {
        ParentVerificationPolicy effective = policy != null ? policy : verificationPolicy;
        ParentVerificationResult checked = effective.verify(this, result);
        this.verification = checked;
        this.verificationHistory.add(DelegatedTaskVerificationEntry.of(this.verificationHistory.size() + 1, effective, checked));
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
    public synchronized List<DelegatedTaskVerificationEntry> verificationHistory() { return List.copyOf(verificationHistory); }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("task_id", taskId);
        m.put("status", status.name());
        m.put("created_at", createdAt.toString());
        m.put("envelope", envelope.toMap());
        m.put("verification_policy", verificationPolicy.toMap());
        m.put("result", result != null ? result.toMap() : null);
        m.put("verification", verification != null ? verification.toMap() : null);
        m.put("latest_verification", verification != null ? verification.toMap() : null);
        m.put("verification_history", verificationHistory().stream().map(DelegatedTaskVerificationEntry::toMap).toList());
        return m;
    }

    @SuppressWarnings("unchecked")
    public static DelegatedTask fromMap(Map<String, Object> m) {
        if (m == null) throw new IllegalArgumentException("map is required");
        DelegatedTaskEnvelope envelope = envelopeFromMap((Map<String, Object>) m.get("envelope"));
        Status status = Status.PENDING;
        try { status = Status.valueOf(String.valueOf(m.getOrDefault("status", "PENDING"))); }
        catch (Exception ignored) {}
        ParentVerificationResult verification = ParentVerificationResult.fromMap((Map<String, Object>) m.get("verification"));
        if (verification == null) verification = ParentVerificationResult.fromMap((Map<String, Object>) m.get("latest_verification"));
        List<DelegatedTaskVerificationEntry> history = new ArrayList<>();
        Object rawHistory = m.get("verification_history");
        if (rawHistory instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> hm) {
                    DelegatedTaskVerificationEntry entry = DelegatedTaskVerificationEntry.fromMap((Map<String, Object>) hm);
                    if (entry != null) history.add(entry);
                }
            }
        }
        return new DelegatedTask(
            String.valueOf(m.get("task_id")),
            envelope,
            ParentVerificationPolicy.fromMap((Map<String, Object>) m.get("verification_policy")),
            parseInstant(m.get("created_at")),
            status,
            DelegatedTaskResult.fromMap((Map<String, Object>) m.get("result")),
            verification,
            history
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
