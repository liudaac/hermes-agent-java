package com.nousresearch.hermes.browser;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Pending approval for a sensitive browser action. */
public record BrowserApprovalRequest(
    String id,
    String tenantId,
    BrowserAction action,
    Map<String, Object> rawArgs,
    String denyReason,
    Status status,
    Instant createdAt,
    Instant expiresAt,
    Instant resolvedAt,
    String resolvedBy,
    String resolutionReason
) {
    public enum Status { PENDING, APPROVED, REJECTED, EXECUTED, FAILED, EXPIRED }

    public BrowserApprovalRequest withStatus(Status next, String actor, String reason) {
        return new BrowserApprovalRequest(id, tenantId, action, rawArgs, denyReason, next, createdAt, expiresAt, Instant.now(), actor, reason);
    }

    public boolean isExpired(Instant now) {
        return status == Status.PENDING && expiresAt != null && now.isAfter(expiresAt);
    }

    public BrowserApprovalRequest expire() {
        return withStatus(Status.EXPIRED, "system", "Browser approval expired");
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("tenant_id", tenantId);
        m.put("status", status.name());
        m.put("created_at", createdAt.toString());
        if (expiresAt != null) m.put("expires_at", expiresAt.toString());
        if (resolvedAt != null) m.put("resolved_at", resolvedAt.toString());
        if (resolvedBy != null) m.put("resolved_by", resolvedBy);
        if (resolutionReason != null) m.put("resolution_reason", resolutionReason);
        m.put("deny_reason", denyReason);
        m.put("action", action.action());
        m.put("actor", action.actor());
        m.put("session_id", action.sessionId() != null ? action.sessionId() : "");
        m.put("url", action.url() != null ? action.url() : "");
        m.put("target", action.target() != null ? action.target() : "");
        m.put("text", action.text() != null ? action.text() : "");
        m.put("instruction", action.instruction() != null ? action.instruction() : "");
        m.put("reason", action.reason() != null ? action.reason() : "");
        m.put("raw_args", rawArgs);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static BrowserApprovalRequest fromMap(Map<String, Object> m) {
        String id = string(m, "id", "");
        String tenantId = string(m, "tenant_id", string(m, "tenantId", ""));
        String statusRaw = string(m, "status", "PENDING");
        Status status = Status.valueOf(statusRaw);
        Map<String, Object> rawArgs = m.get("raw_args") instanceof Map<?, ?> raw
            ? new LinkedHashMap<>((Map<String, Object>) raw)
            : new LinkedHashMap<>();
        BrowserAction action = new BrowserAction(
            string(m, "action", string(rawArgs, "action", "observe")),
            blankToNull(string(m, "session_id", string(rawArgs, "session_id", string(rawArgs, "sessionId", "")))),
            blankToNull(string(m, "url", string(rawArgs, "url", ""))),
            blankToNull(string(m, "target", string(rawArgs, "target", ""))),
            blankToNull(string(m, "text", string(rawArgs, "text", ""))),
            blankToNull(string(m, "instruction", string(rawArgs, "instruction", ""))),
            string(m, "actor", string(rawArgs, "actor", "agent")),
            string(m, "reason", string(rawArgs, "reason", ""))
        );
        return new BrowserApprovalRequest(
            id,
            tenantId,
            action,
            rawArgs,
            string(m, "deny_reason", string(m, "denyReason", "")),
            status,
            instant(m, "created_at", Instant.now()),
            instantOrNull(m, "expires_at"),
            instantOrNull(m, "resolved_at"),
            blankToNull(string(m, "resolved_by", "")),
            blankToNull(string(m, "resolution_reason", ""))
        );
    }

    private static String string(Map<String, Object> m, String key, String fallback) {
        Object v = m.get(key);
        return v == null ? fallback : String.valueOf(v);
    }

    private static Instant instant(Map<String, Object> m, String key, Instant fallback) {
        Instant parsed = instantOrNull(m, key);
        return parsed != null ? parsed : fallback;
    }

    private static Instant instantOrNull(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null || String.valueOf(v).isBlank()) return null;
        try { return Instant.parse(String.valueOf(v)); } catch (Exception ignored) { return null; }
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s; }
}
