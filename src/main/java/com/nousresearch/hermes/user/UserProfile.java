package com.nousresearch.hermes.user;

import java.util.*;

/**
 * Aggregated user profile - the single object loaded when a user interacts
 * with the system. Combines identity, personal memory, capabilities,
 * preferences, and space memberships.
 *
 * <p>This is the <b>user layer</b> of the three-layer main line:
 * <pre>
 *   Organization  ->  Space  ->  User
 * </pre>
 * The profile is constructed by {@link UserManager} and consumed by
 * the agent execution flow alongside {@link com.nousresearch.hermes.space.SpaceContext}.</p>
 */
public class UserProfile {

    private final String userId;
    private String displayName;
    private String email;
    private final Map<String, String> channelBindings;  // "qqbot" -> channelUserId, etc.
    private final List<SpaceMembership> spaces;
    private UserCapability capabilities;
    private UserPreferences preferences;

    // Memory is managed by MemoryStore, not held here directly.
    // UserProfile holds the userId that MemoryStore uses for isolation.

    public UserProfile(String userId) {
        this.userId = userId;
        this.displayName = userId;
        this.email = null;
        this.channelBindings = new LinkedHashMap<>();
        this.spaces = new ArrayList<>();
        this.capabilities = new UserCapability();
        this.preferences = new UserPreferences();
    }

    public String userId() { return userId; }
    public String displayName() { return displayName; }
    public String email() { return email; }
    public Map<String, String> channelBindings() { return channelBindings; }
    public List<SpaceMembership> spaces() { return spaces; }
    public UserCapability capabilities() { return capabilities; }
    public UserPreferences preferences() { return preferences; }

    public void setDisplayName(String name) { this.displayName = name; }
    public void setEmail(String email) { this.email = email; }
    public void setCapabilities(UserCapability c) { this.capabilities = c; }
    public void setPreferences(UserPreferences p) { this.preferences = p; }

    public void bindChannel(String channel, String channelUserId) {
        channelBindings.put(channel, channelUserId);
    }

    public void addSpace(SpaceMembership membership) {
        spaces.removeIf(m -> m.spaceId().equals(membership.spaceId()));
        spaces.add(membership);
    }

    public void removeSpace(String spaceId) {
        spaces.removeIf(m -> m.spaceId().equals(spaceId));
    }

    public Optional<SpaceMembership> findSpace(String spaceId) {
        return spaces.stream().filter(m -> m.spaceId().equals(spaceId)).findFirst();
    }

    /**
     * Check if user has a specific role in a space.
     */
    public boolean hasRole(String spaceId, String role) {
        return findSpace(spaceId)
            .map(m -> m.role().equals(role))
            .orElse(false);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("userId", userId);
        m.put("displayName", displayName);
        m.put("email", email);
        m.put("channelBindings", new LinkedHashMap<>(channelBindings));
        m.put("spaces", spaces.stream().map(SpaceMembership::toMap).toList());
        m.put("capabilities", capabilities.toMap());
        m.put("preferences", preferences.toMap());
        return m;
    }

    @SuppressWarnings("unchecked")
    public static UserProfile fromMap(Map<String, Object> m) {
        UserProfile p = new UserProfile((String) m.get("userId"));
        p.displayName = (String) m.getOrDefault("displayName", p.userId);
        p.email = (String) m.get("email");
        p.channelBindings.putAll((Map<String, String>) m.getOrDefault("channelBindings", Map.of()));
        ((List<Map<String, Object>>) m.getOrDefault("spaces", List.of()))
            .forEach(s -> p.spaces.add(SpaceMembership.fromMap(s)));
        p.capabilities = UserCapability.fromMap((Map<String, Object>) m.get("capabilities"));
        p.preferences = UserPreferences.fromMap((Map<String, Object>) m.get("preferences"));
        return p;
    }
}
