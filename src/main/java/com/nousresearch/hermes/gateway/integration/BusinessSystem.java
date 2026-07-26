package com.nousresearch.hermes.gateway.integration;

import java.time.Instant;
import java.util.Set;

/**
 * D1: Represents a registered business system that can call Hermes API.
 *
 * <p>Each business system gets a unique API Key ({@code ak_xxx}) for authentication.
 * The system is scoped to a tenant and optionally a workspace.</p>
 *
 * @param systemId      unique identifier (e.g. "erp-system")
 * @param displayName   human-readable name
 * @param apiKey        API key for auth (ak_xxx)
 * @param tenantId      owning tenant
 * @param workspaceId   default workspace (may be null = use tenant default)
 * @param allowedScopes permitted operations (read, write, execute, admin)
 * @param webhookUrl    callback URL for async notifications (may be null)
 * @param webhookSecret HMAC secret for webhook signing (may be null)
 * @param active        whether this system can make API calls
 * @param createdAt     registration timestamp
 */
public record BusinessSystem(
        String systemId,
        String displayName,
        String apiKey,
        String tenantId,
        String workspaceId,
        Set<String> allowedScopes,
        String webhookUrl,
        String webhookSecret,
        boolean active,
        Instant createdAt
) {
    /**
     * Check if this system has a given scope.
     */
    public boolean hasScope(String scope) {
        return allowedScopes != null && allowedScopes.contains(scope);
    }

    /**
     * Check if this system can write (send messages, create tasks).
     */
    public boolean canWrite() {
        return hasScope("write") || hasScope("admin");
    }

    /**
     * Check if this system can read (list sessions, query status).
     */
    public boolean canRead() {
        return hasScope("read") || hasScope("write") || hasScope("admin");
    }
}
