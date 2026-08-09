package com.nousresearch.hermes.user;

import java.util.*;

/**
 * User's membership in a space.
 */
public record SpaceMembership(
    String spaceId,
    String spaceName,
    String role,          // "admin" | "member" | "viewer"
    long joinedAt         // epoch millis
) {
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("spaceId", spaceId);
        m.put("spaceName", spaceName);
        m.put("role", role);
        m.put("joinedAt", joinedAt);
        return m;
    }

    public static SpaceMembership fromMap(Map<String, Object> m) {
        return new SpaceMembership(
            (String) m.get("spaceId"),
            (String) m.getOrDefault("spaceName", ""),
            (String) m.getOrDefault("role", "member"),
            ((Number) m.getOrDefault("joinedAt", 0L)).longValue()
        );
    }
}
