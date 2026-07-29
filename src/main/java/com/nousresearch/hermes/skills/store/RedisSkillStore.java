package com.nousresearch.hermes.skills.store;

import com.alibaba.fastjson2.JSON;
import com.nousresearch.hermes.common.RedisOps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Redis-backed {@link SkillStore} for multi-instance deployments.
 *
 * <p>Skill metadata is cached in Redis Hashes with tenant-scoped indices.
 * Changes are broadcast via Redis pub/sub so all instances refresh their
 * local caches in near real-time.</p>
 *
 * <h2>Key layout</h2>
 * <pre>
 *   skill:reg:{tenant}:{skillId}     Hash (metadata fields)
 *   skill:index:{tenant}            Hash (skillId -> name, for listing)
 *   skill:global:index               Hash (skillId -> name, SHARED+SYSTEM)
 *   skill:ver:{skillId}              Hash (version -> config JSON)
 *
 *   Pub/Sub channel: skill:changed:{tenantId}
 *   Pub/Sub channel: skill:changed:global  (SHARED/SYSTEM changes)
 * </pre>
 */
public class RedisSkillStore implements SkillStore {

    private static final Logger logger = LoggerFactory.getLogger(RedisSkillStore.class);

    private final RedisOps redis;
    private final List<SkillChangeListener> listeners = new CopyOnWriteArrayList<>();
    private volatile boolean subscribed = false;

    public RedisSkillStore(RedisOps redis) {
        this.redis = redis;
    }

    // Key builders
    private static String regKey(String tenant, String skillId) {
        return "skill:reg:" + tenant + ":" + skillId;
    }
    private static String indexKey(String tenant) {
        return "skill:index:" + tenant;
    }
    private static String globalIndexKey() {
        return "skill:global:index";
    }
    private static String verKey(String skillId) {
        return "skill:ver:" + skillId;
    }
    private static String changeChannel(String tenant) {
        return "skill:changed:" + tenant;
    }
    private static String globalChangeChannel() {
        return "skill:changed:global";
    }

    // ── Record stored in Redis Hash ─────────────────────────

    private record SkillRecord(
            String id, String tenantId, String name, String description,
            SkillScope scope, SkillType type, boolean enabled,
            String currentVersion, long createdAt, long updatedAt
    ) {}

    private String toJson(SkillRecord r) {
        return JSON.toJSONString(r);
    }

    private SkillRecord fromHash(Map<String, String> h) {
        return new SkillRecord(
            h.get("id"),
            h.get("tenantId"),
            h.get("name"),
            h.get("description"),
            SkillScope.valueOf(h.getOrDefault("scope", "PRIVATE")),
            SkillType.valueOf(h.getOrDefault("type", "CUSTOM")),
            Boolean.parseBoolean(h.getOrDefault("enabled", "true")),
            h.getOrDefault("currentVersion", "1.0.0"),
            Long.parseLong(h.getOrDefault("createdAt", "0")),
            Long.parseLong(h.getOrDefault("updatedAt", "0"))
        );
    }

    // ══════════════════════════════════════════════════════════════════
    //  Registration
    // ══════════════════════════════════════════════════════════════════

    @Override
    public String register(String tenantId, SkillRegistration reg) {
        // Generate ID
        String skillId = redis.incr("skill:seq") + "";
        skillId = "skill_" + skillId;
        long now = Instant.now().toEpochMilli();

        // Check name uniqueness
        SkillInfo existing = findByName(tenantId, reg.name());
        if (existing != null) {
            throw new IllegalStateException(
                "Skill '" + reg.name() + "' already exists for tenant " + tenantId);
        }

        // Store metadata in Hash
        String key = regKey(tenantId, skillId);
        redis.hset(key, "id", skillId);
        redis.hset(key, "tenantId", tenantId);
        redis.hset(key, "name", reg.name());
        redis.hset(key, "description", reg.description() != null ? reg.description() : "");
        redis.hset(key, "scope", reg.scope().name());
        redis.hset(key, "type", reg.type().name());
        redis.hset(key, "enabled", "true");
        redis.hset(key, "currentVersion", "1.0.0");
        redis.hset(key, "createdAt", String.valueOf(now));
        redis.hset(key, "updatedAt", String.valueOf(now));

        // Index
        redis.hset(indexKey(tenantId), skillId, reg.name());
        if (reg.scope() != SkillScope.PRIVATE) {
            redis.hset(globalIndexKey(), skillId, reg.name());
        }

        // Version history
        redis.hset(verKey(skillId), "1.0.0",
            JSON.toJSONString(reg.config()));

        // Publish change
        publishChange(tenantId, skillId, reg.name(), ChangeAction.REGISTERED);

        logger.info("Registered skill: {} ({}) for tenant: {}", reg.name(), skillId, tenantId);
        return skillId;
    }

    @Override
    public void unregister(String tenantId, String skillId) {
        SkillInfo info = get(tenantId, skillId);
        if (info == null) return;
        if (info.scope() == SkillScope.SYSTEM) {
            throw new IllegalStateException("Cannot unregister system skill: " + skillId);
        }

        redis.del(regKey(tenantId, skillId));
        redis.hdel(indexKey(tenantId), skillId);
        if (info.scope() != SkillScope.PRIVATE) {
            redis.hdel(globalIndexKey(), skillId);
        }
        redis.del(verKey(skillId));

        publishChange(tenantId, skillId, info.name(), ChangeAction.UNREGISTERED);
        logger.info("Unregistered skill: {} ({})", info.name(), skillId);
    }

    @Override
    public void enable(String tenantId, String skillId) {
        SkillInfo info = get(tenantId, skillId);
        if (info == null || info.enabled()) return;
        redis.hset(regKey(tenantId, skillId), "enabled", "true");
        redis.hset(regKey(tenantId, skillId), "updatedAt",
            String.valueOf(Instant.now().toEpochMilli()));
        publishChange(tenantId, skillId, info.name(), ChangeAction.ENABLED);
    }

    @Override
    public void disable(String tenantId, String skillId) {
        SkillInfo info = get(tenantId, skillId);
        if (info == null || !info.enabled()) return;
        redis.hset(regKey(tenantId, skillId), "enabled", "false");
        redis.hset(regKey(tenantId, skillId), "updatedAt",
            String.valueOf(Instant.now().toEpochMilli()));
        publishChange(tenantId, skillId, info.name(), ChangeAction.DISABLED);
    }

    // ══════════════════════════════════════════════════════════════════
    //  Discovery
    // ══════════════════════════════════════════════════════════════════

    @Override
    public List<SkillInfo> list(String tenantId, SkillScope scope) {
        List<SkillInfo> result = new ArrayList<>();

        // Tenant's own skills
        Map<String, String> tenantSkills = redis.hgetAll(indexKey(tenantId));
        for (String skillId : tenantSkills.keySet()) {
            SkillInfo info = get(tenantId, skillId);
            if (info != null && (scope == null || info.scope() == scope)) {
                result.add(info);
            }
        }

        // SHARED + SYSTEM from global index
        if (scope == SkillScope.SHARED || scope == SkillScope.SYSTEM || scope == null) {
            Map<String, String> globalSkills = redis.hgetAll(globalIndexKey());
            for (String skillId : globalSkills.keySet()) {
                // Skip if already in tenant list
                if (tenantSkills.containsKey(skillId)) continue;
                // Get the skill (it belongs to another tenant but is shared)
                SkillInfo info = getGlobalSkill(skillId);
                if (info != null && (scope == null || info.scope() == scope)) {
                    result.add(info);
                }
            }
        }

        return result.stream()
            .sorted((a, b) -> b.updatedAt().compareTo(a.updatedAt()))
            .collect(Collectors.toList());
    }

    @Override
    public SkillInfo get(String tenantId, String skillId) {
        String key = regKey(tenantId, skillId);
        if (!redis.exists(key)) {
            // Maybe it's a shared skill from another tenant
            return getGlobalSkill(skillId);
        }
        Map<String, String> h = redis.hgetAll(key);
        if (h.isEmpty()) return null;
        return toSkillInfo(fromHash(h));
    }

    @Override
    public SkillInfo findByName(String tenantId, String name) {
        // Search tenant index
        Map<String, String> tenantSkills = redis.hgetAll(indexKey(tenantId));
        for (Map.Entry<String, String> e : tenantSkills.entrySet()) {
            if (name.equals(e.getValue())) {
                return get(tenantId, e.getKey());
            }
        }
        // Search global index
        Map<String, String> globalSkills = redis.hgetAll(globalIndexKey());
        for (Map.Entry<String, String> e : globalSkills.entrySet()) {
            if (name.equals(e.getValue())) {
                return getGlobalSkill(e.getKey());
            }
        }
        return null;
    }

    // ══════════════════════════════════════════════════════════════════
    //  Version Management
    // ══════════════════════════════════════════════════════════════════

    @Override
    public void publishVersion(String tenantId, String skillId,
                                String version, SkillConfig config) {
        SkillInfo info = get(tenantId, skillId);
        if (info == null) return;

        redis.hset(verKey(skillId), version, JSON.toJSONString(config));
        redis.hset(regKey(tenantId, skillId), "currentVersion", version);
        redis.hset(regKey(tenantId, skillId), "updatedAt",
            String.valueOf(Instant.now().toEpochMilli()));

        publishChange(tenantId, skillId, info.name(), ChangeAction.VERSION_PUBLISHED);
    }

    @Override
    public SkillConfig getActiveVersion(String tenantId, String skillId) {
        SkillInfo info = get(tenantId, skillId);
        if (info == null) return null;
        String json = redis.hget(verKey(skillId), info.currentVersion());
        if (json == null) return null;
        return JSON.parseObject(json, SkillConfig.class);
    }

    @Override
    public void rollback(String tenantId, String skillId, String version) {
        SkillInfo info = get(tenantId, skillId);
        if (info == null) return;

        Map<String, String> versions = redis.hgetAll(verKey(skillId));
        if (!versions.containsKey(version)) {
            throw new IllegalArgumentException("Version not found: " + version);
        }

        redis.hset(regKey(tenantId, skillId), "currentVersion", version);
        redis.hset(regKey(tenantId, skillId), "updatedAt",
            String.valueOf(Instant.now().toEpochMilli()));

        publishChange(tenantId, skillId, info.name(), ChangeAction.ROLLED_BACK);
    }

    @Override
    public List<String> listVersions(String tenantId, String skillId) {
        Map<String, String> versions = redis.hgetAll(verKey(skillId));
        return new ArrayList<>(versions.keySet());
    }

    // ══════════════════════════════════════════════════════════════════
    //  Change Notification (pub/sub)
    // ══════════════════════════════════════════════════════════════════

    @Override
    public void subscribeChanges(SkillChangeListener listener) {
        listeners.add(listener);
    }

    /**
     * Start listening to Redis pub/sub for skill changes.
     * Call this once during application bootstrap.
     */
    public void startSubscribing() {
        if (subscribed) return;
        subscribed = true;

        // Subscribe to global changes
        redis.subscribePattern("skill:changed:*", (channel, message) -> {
            try {
                SkillChangeEvent event = JSON.parseObject(message, SkillChangeEvent.class);
                for (SkillChangeListener listener : listeners) {
                    try {
                        listener.onSkillChange(event);
                    } catch (Exception e) {
                        logger.warn("Skill change listener error: {}", e.getMessage());
                    }
                }
            } catch (Exception e) {
                logger.warn("Failed to parse skill change event: {}", e.getMessage());
            }
        });

        logger.info("RedisSkillStore subscribed to skill:changed:* pub/sub");
    }

    private void publishChange(String tenantId, String skillId,
                                String skillName, ChangeAction action) {
        SkillChangeEvent event = new SkillChangeEvent(
            tenantId, skillId, skillName, action, Instant.now()
        );
        String channel = changeChannel(tenantId);
        redis.publish(channel, JSON.toJSONString(event));

        // Also publish to global channel for SHARED/SYSTEM
        SkillInfo info = get(tenantId, skillId);
        if (info != null && info.scope() != SkillScope.PRIVATE) {
            redis.publish(globalChangeChannel(), JSON.toJSONString(event));
        }
    }

    // ── Helpers ─────────────────────────────────────────────

    private SkillInfo getGlobalSkill(String skillId) {
        // Search all tenants for this skillId
        // This is a limitation of the Redis-only approach without a reverse index
        // For Sprint C (Postgres), this will be a simple SQL query
        // For now, check the global index and try to find the owning tenant
        Map<String, String> globalSkills = redis.hgetAll(globalIndexKey());
        String name = globalSkills.get(skillId);
        if (name == null) return null;

        // Try to find the skill by checking a global skill registry
        String tenant = redis.hget("skill:id2tenant:" + skillId, "tenant");
        if (tenant == null) return null;

        String key = regKey(tenant, skillId);
        if (!redis.exists(key)) return null;
        Map<String, String> h = redis.hgetAll(key);
        if (h.isEmpty()) return null;
        return toSkillInfo(fromHash(h));
    }

    private SkillInfo toSkillInfo(SkillRecord r) {
        Map<String, String> versions = redis.hgetAll(verKey(r.id()));
        List<String> versionList = new ArrayList<>(versions.keySet());

        return new SkillInfo(
            r.id(), r.tenantId(), r.name(), r.description(),
            r.scope(), r.type(), r.enabled(),
            r.currentVersion(),
            Instant.ofEpochMilli(r.createdAt()),
            Instant.ofEpochMilli(r.updatedAt()),
            versionList
        );
    }
}
