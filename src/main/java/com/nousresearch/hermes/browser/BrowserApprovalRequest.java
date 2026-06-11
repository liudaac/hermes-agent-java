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
    Instant resolvedAt,
    String resolvedBy,
    String resolutionReason
) {
    public enum Status { PENDING, APPROVED, REJECTED, EXECUTED, FAILED }

    public BrowserApprovalRequest withStatus(Status next, String actor, String reason) {
        return new BrowserApprovalRequest(id, tenantId, action, rawArgs, denyReason, next, createdAt, Instant.now(), actor, reason);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("tenant_id", tenantId);
        m.put("status", status.name());
        m.put("created_at", createdAt.toString());
        if (resolvedAt != null) m.put("resolved_at", resolvedAt.toString());
        if (resolvedBy != null) m.put("resolved_by", resolvedBy);
        if (resolutionReason != null) m.put("resolution_reason", resolutionReason);
        m.put("deny_reason", denyReason);
        m.put("action", action.action());
        m.put("actor", action.actor());
        m.put("url", action.url() != null ? action.url() : "");
        m.put("target", action.target() != null ? action.target() : "");
        m.put("text", action.text() != null ? action.text() : "");
        m.put("instruction", action.instruction() != null ? action.instruction() : "");
        m.put("reason", action.reason() != null ? action.reason() : "");
        m.put("raw_args", rawArgs);
        return m;
    }
}
