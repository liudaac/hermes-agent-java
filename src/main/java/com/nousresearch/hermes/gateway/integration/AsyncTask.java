package com.nousresearch.hermes.gateway.integration;

import java.time.Instant;
import java.util.Map;

/**
 * D3: Represents an async task submitted by a business system.
 *
 * <p>State machine: PENDING -> RUNNING -> COMPLETED / FAILED / CANCELLED</p>
 */
public record AsyncTask(
        String taskId,
        String tenantId,
        String systemId,
        String workspaceId,
        String agentId,
        String sessionId,
        String input,
        String status,
        String result,
        String error,
        int priority,
        int timeoutSeconds,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt
) {
    public boolean isTerminal() {
        return "COMPLETED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status);
    }

    public Map<String, Object> toApi() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("taskId", taskId);
        m.put("tenantId", tenantId);
        m.put("systemId", systemId);
        m.put("workspaceId", workspaceId);
        m.put("agentId", agentId);
        m.put("status", status);
        m.put("result", result);
        m.put("error", error);
        m.put("priority", priority);
        if (createdAt != null) m.put("createdAt", createdAt.toString());
        if (startedAt != null) m.put("startedAt", startedAt.toString());
        if (completedAt != null) m.put("completedAt", completedAt.toString());
        return m;
    }
}
