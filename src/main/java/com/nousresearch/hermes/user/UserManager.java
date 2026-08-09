package com.nousresearch.hermes.user;

import com.nousresearch.hermes.auth.UserIdentityResolver;
import com.nousresearch.hermes.improvement.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages user profiles across the platform.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Load / cache {@link UserProfile} by userId</li>
 *   <li>Resolve channel-specific IDs to unified userId (delegates to {@link UserIdentityResolver})</li>
 *   <li>Apply user-level adaptation signals from the improvement engine</li>
 *   <li>Merge user capabilities with space capabilities</li>
 * </ul></p>
 *
 * <p>This is the <b>user-layer manager</b> in the three-layer architecture.
 * It sits below the space layer and above the identity system.</p>
 */
public class UserManager {

    private static final Logger logger = LoggerFactory.getLogger(UserManager.class);

    private final UserIdentityResolver identityResolver;
    private final Path profilesDir;
    private final ConcurrentHashMap<String, UserProfile> cache = new ConcurrentHashMap<>();

    public UserManager(UserIdentityResolver identityResolver) {
        this(identityResolver, null);
    }

    public UserManager(UserIdentityResolver identityResolver, Path dataDir) {
        this.identityResolver = identityResolver;
        this.profilesDir = dataDir != null ? dataDir.resolve("user-profiles") : null;
        if (profilesDir != null) {
            try {
                Files.createDirectories(profilesDir);
            } catch (Exception e) {
                logger.warn("Failed to create user profiles directory: {}", e.getMessage());
            }
        }
    }

    /**
     * Resolve a channel-specific user ID to the unified internal userId.
     */
    public String resolveUserId(String channel, String channelUserId) {
        return identityResolver.resolveUserId(channel, channelUserId);
    }

    /**
     * Load a user profile by userId. Creates a new profile on first access.
     */
    public UserProfile load(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId cannot be null or blank");
        }
        return cache.computeIfAbsent(userId, this::loadOrCreate);
    }

    /**
     * Load or create a profile for a channel-specific user.
     * Convenience method: resolves identity then loads profile.
     */
    public UserProfile loadByChannel(String channel, String channelUserId) {
        String userId = resolveUserId(channel, channelUserId);
        if (userId == null) return null;
        return load(userId);
    }

    /**
     * Save a user profile (persist + update cache).
     */
    public void save(UserProfile profile) {
        cache.put(profile.userId(), profile);
        if (profilesDir != null) {
            try {
                Path file = profilesDir.resolve(profile.userId() + ".json");
                String json = com.alibaba.fastjson2.JSON.toJSONString(profile.toMap());
                Files.writeString(file, json);
            } catch (Exception e) {
                logger.warn("Failed to persist user profile {}: {}", profile.userId(), e.getMessage());
            }
        }
    }

    /**
     * Apply an improvement signal to the user's profile.
     * This is the user-level adaptation entry point.
     */
    public void adapt(String userId, ImprovementSignal signal) {
        UserProfile profile = load(userId);
        boolean changed = false;

        switch (signal.type()) {
            case USER_CORRECTION -> {
                // User corrected the agent's behavior -> update preferences
                String key = signal.metadata().getOrDefault("preference_key", "").toString();
                String value = signal.metadata().getOrDefault("preference_value", "").toString();
                if (!key.isEmpty() && !value.isEmpty()) {
                    profile.preferences().setExtra(key, value);
                    changed = true;
                    logger.info("User {} preference adapted: {}={}", userId, key, value);
                }
            }
            case EXPLICIT_FEEDBACK -> {
                // User gave explicit feedback -> store as memory metadata
                String feedback = signal.metadata().getOrDefault("feedback", "").toString();
                if (!feedback.isEmpty()) {
                    profile.preferences().setExtra("lastFeedback", feedback);
                    changed = true;
                    logger.info("User {} explicit feedback recorded", userId);
                }
            }
            case REPEAT_PATTERN -> {
                // User repeatedly uses a tool/skill -> add to frequent tools
                String tool = signal.metadata().getOrDefault("tool", "").toString();
                if (!tool.isEmpty()) {
                    profile.capabilities().addFrequentTool(tool);
                    changed = true;
                    logger.info("User {} frequent tool recorded: {}", userId, tool);
                }
            }
            default -> { /* no-op for unhandled signal types */ }
        }

        if (changed) {
            save(profile);
        }
    }

    /**
     * Merge user capabilities with space capabilities.
     * Returns the effective capability set for this user in this space.
     */
    public MergedCapability mergeCapabilities(UserProfile user, Set<String> spaceCapabilities) {
        Set<String> effective = new LinkedHashSet<>();
        // Space capabilities first (team foundation)
        if (spaceCapabilities != null) {
            effective.addAll(spaceCapabilities);
        }
        // User's personal capabilities on top
        effective.addAll(user.capabilities().personalSkills());
        // Remove hidden ones
        effective.removeAll(user.capabilities().hiddenCapabilities());
        return new MergedCapability(effective, user.capabilities().frequentTools(),
                                     user.capabilities().shortcuts());
    }

    /**
     * List all cached user profiles (for admin UI).
     */
    public List<UserProfile> listCached() {
        return new ArrayList<>(cache.values());
    }

    /**
     * Invalidate cache for a specific user.
     */
    public void invalidate(String userId) {
        cache.remove(userId);
    }

    private UserProfile loadOrCreate(String userId) {
        if (profilesDir != null) {
            Path file = profilesDir.resolve(userId + ".json");
            if (Files.exists(file)) {
                try {
                    String json = Files.readString(file);
                    Map<String, Object> map = com.alibaba.fastjson2.JSON.parseObject(json);
                    return UserProfile.fromMap(map);
                } catch (Exception e) {
                    logger.warn("Failed to load user profile {}: {}", userId, e.getMessage());
                }
            }
        }
        // Create new profile
        UserProfile profile = new UserProfile(userId);
        logger.info("Created new user profile: {}", userId);
        return profile;
    }

    /**
     * Result of merging user + space capabilities.
     */
    public record MergedCapability(
        Set<String> effectiveSkills,
        Set<String> frequentTools,
        Map<String, String> shortcuts
    ) {
        public boolean hasSkill(String skillId) {
            return effectiveSkills.contains(skillId);
        }
    }
}
