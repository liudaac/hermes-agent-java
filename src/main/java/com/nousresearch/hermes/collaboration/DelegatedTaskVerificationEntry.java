package com.nousresearch.hermes.collaboration;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable audit-style history entry for each parent verification attempt. */
public record DelegatedTaskVerificationEntry(
    String sequenceId,
    ParentVerificationPolicy policy,
    ParentVerificationResult result,
    Instant recordedAt
) {
    public DelegatedTaskVerificationEntry {
        policy = policy != null ? policy : ParentVerificationPolicy.strict();
        result = result != null ? result : ParentVerificationResult.rejected(java.util.List.of("missing verification result"));
        recordedAt = recordedAt != null ? recordedAt : Instant.now();
    }

    public static DelegatedTaskVerificationEntry of(int sequence, ParentVerificationPolicy policy, ParentVerificationResult result) {
        return new DelegatedTaskVerificationEntry("verification_" + Math.max(1, sequence), policy, result, Instant.now());
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sequence_id", sequenceId);
        m.put("policy", policy.toMap());
        m.put("result", result.toMap());
        m.put("recorded_at", recordedAt.toString());
        return m;
    }

    @SuppressWarnings("unchecked")
    public static DelegatedTaskVerificationEntry fromMap(Map<String, Object> m) {
        if (m == null) return null;
        return new DelegatedTaskVerificationEntry(
            stringOrDefault(m.get("sequence_id"), "verification_1"),
            ParentVerificationPolicy.fromMap((Map<String, Object>) m.get("policy")),
            ParentVerificationResult.fromMap((Map<String, Object>) m.get("result")),
            parseInstant(m.get("recorded_at"))
        );
    }

    private static Instant parseInstant(Object value) {
        if (value == null) return Instant.now();
        try { return Instant.parse(String.valueOf(value)); }
        catch (Exception ignored) { return Instant.now(); }
    }

    private static String stringOrDefault(Object value, String fallback) {
        if (value == null) return fallback;
        String s = String.valueOf(value);
        return s.isBlank() || "null".equals(s) ? fallback : s;
    }
}
