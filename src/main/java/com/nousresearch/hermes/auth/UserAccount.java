package com.nousresearch.hermes.auth;

import java.time.Instant;
import java.util.Set;

/**
 * D5: User entity for RBAC.
 *
 * <p>Represents a human user or a system user authenticated via SSO or API Key.</p>
 *
 * @param userId       unique user ID (UUID)
 * @param email        email address
 * @param displayName  human-readable name
 * @param ssoSubject   SSO provider subject (for SSO-linked users)
 * @param active       whether the user can log in
 * @param createdAt    registration timestamp
 */
public record UserAccount(
        String userId,
        String email,
        String displayName,
        String ssoSubject,
        boolean active,
        Instant createdAt
) {
    /**
     * Roles supported by the RBAC system.
     */
    public enum Role {
        ADMIN,      // full access including member management
        OPERATOR,   // send tasks, manage agents, view results
        VIEWER,     // read-only (status, usage, billing)
        API_ONLY;   // API calls only, no portal UI access

        public boolean canRead() { return true; }
        public boolean canWrite() { return this == ADMIN || this == OPERATOR; }
        public boolean canAdmin() { return this == ADMIN; }
        public boolean canAccessPortal() { return this != API_ONLY; }

        public static Role fromString(String s) {
            if (s == null) return VIEWER;
            return switch (s.toUpperCase()) {
                case "ADMIN" -> ADMIN;
                case "OPERATOR" -> OPERATOR;
                case "VIEWER" -> VIEWER;
                case "API-ONLY", "API_ONLY" -> API_ONLY;
                default -> VIEWER;
            };
        }
    }
}
