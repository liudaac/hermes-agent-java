package com.nousresearch.hermes.skills.store;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Centralised skill registry for multi-tenant skill management.
 *
 * <p>Provides registration, discovery, versioning, and cross-instance
 * distribution of skills. All operations are scoped by tenant ID.</p>
 *
 * <h2>Implementations</h2>
 * <ul>
 *   <li>{@link LocalSkillStore} &mdash; in-memory (dev / single-node).</li>
 *   <li>{@code RedisSkillStore} &mdash; Redis cache + pub/sub broadcast (Sprint B).</li>
 *   <li>{@code PostgresSkillStore} &mdash; persistent + version rollback (Sprint C).</li>
 * </ul>
 */
public interface SkillStore {

    // ══════════════════════════════════════════════════════════════════
    //  Registration
    // ══════════════════════════════════════════════════════════════════

    /**
     * Register a new skill.
     *
     * @return the generated skill ID
     */
    String register(String tenantId, SkillRegistration reg);

    /**
     * Unregister (remove) a skill.
     */
    void unregister(String tenantId, String skillId);

    /**
     * Enable a disabled skill.
     */
    void enable(String tenantId, String skillId);

    /**
     * Disable a skill (keep it registered but inactive).
     */
    void disable(String tenantId, String skillId);

    // ══════════════════════════════════════════════════════════════════
    //  Discovery
    // ══════════════════════════════════════════════════════════════════

    /**
     * List skills by scope for a tenant.
     */
    List<SkillInfo> list(String tenantId, SkillScope scope);

    /**
     * Get skill details by ID.
     */
    SkillInfo get(String tenantId, String skillId);

    /**
     * Find a skill by name.
     */
    SkillInfo findByName(String tenantId, String name);

    // ══════════════════════════════════════════════════════════════════
    //  Version Management
    // ══════════════════════════════════════════════════════════════════

    /**
     * Publish a new version of a skill.
     */
    void publishVersion(String tenantId, String skillId,
                        String version, SkillConfig config);

    /**
     * Get the active (current) version config of a skill.
     */
    SkillConfig getActiveVersion(String tenantId, String skillId);

    /**
     * Roll back to a previous version.
     */
    void rollback(String tenantId, String skillId, String version);

    /**
     * List all published versions of a skill.
     */
    List<String> listVersions(String tenantId, String skillId);

    // ══════════════════════════════════════════════════════════════════
    //  Change Notification (for multi-instance sync)
    // ══════════════════════════════════════════════════════════════════

    /**
     * Subscribe to skill changes (register/unregister/enable/disable/publishVersion).
     * Called when any skill change occurs in the store.
     */
    void subscribeChanges(SkillChangeListener listener);

    /**
     * Listener interface for skill change events.
     */
    @FunctionalInterface
    interface SkillChangeListener {
        void onSkillChange(SkillChangeEvent event);
    }

    /**
     * Event describing a skill change.
     */
    record SkillChangeEvent(
            String tenantId,
            String skillId,
            String skillName,
            ChangeAction action,
            Instant timestamp
    ) {}

    enum ChangeAction {
        REGISTERED, UNREGISTERED, ENABLED, DISABLED,
        VERSION_PUBLISHED, ROLLED_BACK
    }

    // ══════════════════════════════════════════════════════════════════
    //  Data types
    // ══════════════════════════════════════════════════════════════════

    enum SkillScope {
        /** Private to one tenant. */
        PRIVATE,
        /** Shared across all tenants (visible to all). */
        SHARED,
        /** Built-in system skill (cannot be unregistered). */
        SYSTEM
    }

    enum SkillType {
        /** Built-in skill shipped with Hermes. */
        BUILTIN,
        /** Custom skill created by tenant. */
        CUSTOM,
        /** External connector (HTTP/SQL/Webhook). */
        CONNECTOR
    }

    /**
     * Skill registration data.
     */
    record SkillRegistration(
            String name,
            String description,
            SkillScope scope,
            SkillType type,
            SkillConfig config
    ) {
        public SkillRegistration {
            if (scope == null) scope = SkillScope.PRIVATE;
            if (type == null) type = SkillType.CUSTOM;
            if (config == null) config = SkillConfig.empty();
        }
    }

    /**
     * Skill metadata + current state.
     */
    record SkillInfo(
            String id,
            String tenantId,
            String name,
            String description,
            SkillScope scope,
            SkillType type,
            boolean enabled,
            String currentVersion,
            Instant createdAt,
            Instant updatedAt,
            List<String> versions
    ) {}

    /**
     * Skill configuration (tool definitions, prompt templates, permissions).
     */
    record SkillConfig(
            String content,
            Map<String, Object> toolDefinitions,
            List<String> requiredPermissions,
            Map<String, String> metadata
    ) {
        public static SkillConfig empty() {
            return new SkillConfig("", Map.of(), List.of(), Map.of());
        }
    }
}
