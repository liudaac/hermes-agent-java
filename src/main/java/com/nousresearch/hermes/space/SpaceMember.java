package com.nousresearch.hermes.space;

import java.util.*;

/**
 * Space member - a user's membership in a space.
 */
public record SpaceMember(
    String userId,
    String displayName,
    String role,          // "admin" | "member" | "viewer"
    long joinedAt,
    long lastActiveAt
) {
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("userId", userId);
        m.put("displayName", displayName);
        m.put("role", role);
        m.put("joinedAt", joinedAt);
        m.put("lastActiveAt", lastActiveAt);
        return m;
    }

    public static SpaceMember fromMap(Map<String, Object> m) {
        return new SpaceMember(
            (String) m.get("userId"),
            (String) m.getOrDefault("displayName", ""),
            (String) m.getOrDefault("role", "member"),
            ((Number) m.getOrDefault("joinedAt", 0L)).longValue(),
            ((Number) m.getOrDefault("lastActiveAt", 0L)).longValue()
        );
    }
}
