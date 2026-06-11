package com.nousresearch.hermes.collaboration;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Parent-side verification outcome for a delegated task result. */
public record ParentVerificationResult(
    boolean accepted,
    String status,
    List<String> reasons,
    Instant verifiedAt
) {
    public ParentVerificationResult {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
        verifiedAt = verifiedAt != null ? verifiedAt : Instant.now();
    }

    public static ParentVerificationResult accept() {
        return new ParentVerificationResult(true, "ACCEPTED", List.of("verification passed"), Instant.now());
    }

    public static ParentVerificationResult rejected(List<String> reasons) {
        return new ParentVerificationResult(false, "REJECTED", reasons, Instant.now());
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("accepted", accepted);
        m.put("status", status);
        m.put("reasons", reasons);
        m.put("verified_at", verifiedAt.toString());
        return m;
    }

    @SuppressWarnings("unchecked")
    public static ParentVerificationResult fromMap(Map<String, Object> m) {
        if (m == null) return null;
        List<String> reasons = m.get("reasons") instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
        return new ParentVerificationResult(
            Boolean.parseBoolean(String.valueOf(m.getOrDefault("accepted", "false"))),
            String.valueOf(m.getOrDefault("status", "REJECTED")),
            reasons,
            parseInstant(m.get("verified_at"))
        );
    }

    private static Instant parseInstant(Object value) {
        if (value == null) return Instant.now();
        try { return Instant.parse(String.valueOf(value)); }
        catch (Exception ignored) { return Instant.now(); }
    }
}
