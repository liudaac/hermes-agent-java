package com.nousresearch.hermes.skills.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * In-memory {@link SkillStore} implementation. Zero external dependencies.
 *
 * <p>Used by default in LOCAL mode and as a fallback in CLUSTER mode.
 * Skill data is lost on restart (no persistence). For multi-instance
 * deployments, use RedisSkillStore (Sprint B).</p>
 */
public class LocalSkillStore implements SkillStore {

    private static final Logger logger = LoggerFactory.getLogger(LocalSkillStore.class);

    // skillId -> SkillRecord
    private final ConcurrentHashMap<String, SkillRecord> skills = new ConcurrentHashMap<>();
    // tenantId -> Set<skillId> (index for fast listing)
    private final ConcurrentHashMap<String, Set<String>> tenantIndex = new ConcurrentHashMap<>();
    // global index for SHARED + SYSTEM skills
    private final Set<String> globalIndex = ConcurrentHashMap.newKeySet();
    // skillId -> version -> SkillConfig
    private final ConcurrentHashMap<String, LinkedHashMap<String, SkillConfig>> versionHistory = new ConcurrentHashMap<>();
    // change listeners
    private final List<SkillChangeListener> listeners = new CopyOnWriteArrayList<>();
    // ID counter
    private final AtomicInteger idCounter = new AtomicInteger(0);

    private record SkillRecord(
            String id,
            String tenantId,
            String name,
            String description,
            SkillScope scope,
            SkillType type,
            boolean enabled,
            String currentVersion,
            Instant createdAt,
            Instant updatedAt
    ) {}

    // ══════════════════════════════════════════════════════════════════
    //  Registration
    // ══════════════════════════════════════════════════════════════════

    @Override
    public String register(String tenantId, SkillRegistration reg) {
        String skillId = "skill_" + idCounter.incrementAndGet();
        Instant now = Instant.now();

        // Check name uniqueness within tenant
        if (findByName(tenantId, reg.name()) != null) {
            throw new IllegalStateException(
                "Skill '" + reg.name() + "' already exists for tenant " + tenantId);
        }

        SkillRecord record = new SkillRecord(
            skillId, tenantId, reg.name(), reg.description(),
            reg.scope(), reg.type(), true,
            "1.0.0", now, now
        );
        skills.put(skillId, record);

        // Index
        tenantIndex.computeIfAbsent(tenantId, k -> ConcurrentHashMap.newKeySet()).add(skillId);
        if (reg.scope() != SkillScope.PRIVATE) {
            globalIndex.add(skillId);
        }

        // Version history
        LinkedHashMap<String, SkillConfig> versions = new LinkedHashMap<>();
        versions.put("1.0.0", reg.config());
        versionHistory.put(skillId, versions);

        notifyChange(tenantId, skillId, reg.name(), ChangeAction.REGISTERED);

        logger.info("Registered skill: {} ({}) for tenant: {}", reg.name(), skillId, tenantId);
        return skillId;
    }

    @Override
    public void unregister(String tenantId, String skillId) {
        SkillRecord record = skills.get(skillId);
        if (record == null || !record.tenantId().equals(tenantId)) return;
        if (record.scope() == SkillScope.SYSTEM) {
            throw new IllegalStateException("Cannot unregister system skill: " + skillId);
        }

        skills.remove(skillId);
        Set<String> tenantSkills = tenantIndex.get(tenantId);
        if (tenantSkills != null) tenantSkills.remove(skillId);
        globalIndex.remove(skillId);
        versionHistory.remove(skillId);

        notifyChange(tenantId, skillId, record.name(), ChangeAction.UNREGISTERED);
        logger.info("Unregistered skill: {} ({})", record.name(), skillId);
    }

    @Override
    public void enable(String tenantId, String skillId) {
        SkillRecord record = skills.get(skillId);
        if (record == null || !record.tenantId().equals(tenantId)) return;
        if (record.enabled()) return;

        record = new SkillRecord(
            record.id(), record.tenantId(), record.name(), record.description(),
            record.scope(), record.type(), true, record.currentVersion(),
            record.createdAt(), Instant.now()
        );
        skills.put(skillId, record);

        notifyChange(tenantId, skillId, record.name(), ChangeAction.ENABLED);
    }

    @Override
    public void disable(String tenantId, String skillId) {
        SkillRecord record = skills.get(skillId);
        if (record == null || !record.tenantId().equals(tenantId)) return;
        if (!record.enabled()) return;

        record = new SkillRecord(
            record.id(), record.tenantId(), record.name(), record.description(),
            record.scope(), record.type(), false, record.currentVersion(),
            record.createdAt(), Instant.now()
        );
        skills.put(skillId, record);

        notifyChange(tenantId, skillId, record.name(), ChangeAction.DISABLED);
    }

    // ══════════════════════════════════════════════════════════════════
    //  Discovery
    // ══════════════════════════════════════════════════════════════════

    @Override
    public List<SkillInfo> list(String tenantId, SkillScope scope) {
        List<SkillInfo> result = new ArrayList<>();

        // Tenant's own skills
        Set<String> tenantSkills = tenantIndex.get(tenantId);
        if (tenantSkills != null) {
            for (String skillId : tenantSkills) {
                SkillRecord r = skills.get(skillId);
                if (r == null) continue;
                if (scope == null || r.scope() == scope) {
                    result.add(toSkillInfo(r));
                }
            }
        }

        // If scope is SHARED or SYSTEM, also include global skills
        if (scope == SkillScope.SHARED || scope == SkillScope.SYSTEM || scope == null) {
            for (String skillId : globalIndex) {
                SkillRecord r = skills.get(skillId);
                if (r == null) continue;
                // Skip if already in tenant list
                if (tenantSkills != null && tenantSkills.contains(skillId)) continue;
                if (scope == null || r.scope() == scope) {
                    result.add(toSkillInfo(r));
                }
            }
        }

        return result.stream()
            .sorted((a, b) -> b.updatedAt().compareTo(a.updatedAt()))
            .collect(Collectors.toList());
    }

    @Override
    public SkillInfo get(String tenantId, String skillId) {
        SkillRecord r = skills.get(skillId);
        if (r == null) return null;
        // Check visibility: own skill or shared/system
        if (!r.tenantId().equals(tenantId) && r.scope() == SkillScope.PRIVATE) {
            return null;
        }
        return toSkillInfo(r);
    }

    @Override
    public SkillInfo findByName(String tenantId, String name) {
        Set<String> tenantSkills = tenantIndex.get(tenantId);
        if (tenantSkills != null) {
            for (String skillId : tenantSkills) {
                SkillRecord r = skills.get(skillId);
                if (r != null && r.name().equals(name)) {
                    return toSkillInfo(r);
                }
            }
        }
        // Check global (SHARED + SYSTEM)
        for (String skillId : globalIndex) {
            SkillRecord r = skills.get(skillId);
            if (r != null && r.name().equals(name)) {
                return toSkillInfo(r);
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
        SkillRecord r = skills.get(skillId);
        if (r == null || !r.tenantId().equals(tenantId)) return;

        versionHistory.computeIfAbsent(skillId, k -> new LinkedHashMap<>())
            .put(version, config);

        // Update current version
        r = new SkillRecord(
            r.id(), r.tenantId(), r.name(), r.description(),
            r.scope(), r.type(), r.enabled(), version,
            r.createdAt(), Instant.now()
        );
        skills.put(skillId, r);

        notifyChange(tenantId, skillId, r.name(), ChangeAction.VERSION_PUBLISHED);
        logger.info("Published version {} for skill: {} ({})", version, r.name(), skillId);
    }

    @Override
    public SkillConfig getActiveVersion(String tenantId, String skillId) {
        SkillRecord r = skills.get(skillId);
        if (r == null || !r.tenantId().equals(tenantId)) return null;
        LinkedHashMap<String, SkillConfig> versions = versionHistory.get(skillId);
        if (versions == null || r.currentVersion() == null) return null;
        return versions.get(r.currentVersion());
    }

    @Override
    public void rollback(String tenantId, String skillId, String version) {
        SkillRecord r = skills.get(skillId);
        if (r == null || !r.tenantId().equals(tenantId)) return;

        LinkedHashMap<String, SkillConfig> versions = versionHistory.get(skillId);
        if (versions == null || !versions.containsKey(version)) {
            throw new IllegalArgumentException("Version not found: " + version);
        }

        r = new SkillRecord(
            r.id(), r.tenantId(), r.name(), r.description(),
            r.scope(), r.type(), r.enabled(), version,
            r.createdAt(), Instant.now()
        );
        skills.put(skillId, r);

        notifyChange(tenantId, skillId, r.name(), ChangeAction.ROLLED_BACK);
        logger.info("Rolled back skill {} to version {}", skillId, version);
    }

    @Override
    public List<String> listVersions(String tenantId, String skillId) {
        LinkedHashMap<String, SkillConfig> versions = versionHistory.get(skillId);
        if (versions == null) return List.of();
        return new ArrayList<>(versions.keySet());
    }

    // ══════════════════════════════════════════════════════════════════
    //  Change Notification
    // ══════════════════════════════════════════════════════════════════

    @Override
    public void subscribeChanges(SkillChangeListener listener) {
        listeners.add(listener);
    }

    private void notifyChange(String tenantId, String skillId,
                              String skillName, ChangeAction action) {
        SkillChangeEvent event = new SkillChangeEvent(
            tenantId, skillId, skillName, action, Instant.now()
        );
        for (SkillChangeListener listener : listeners) {
            try {
                listener.onSkillChange(event);
            } catch (Exception e) {
                logger.warn("Skill change listener error: {}", e.getMessage());
            }
        }
    }

    // ── Helper ──────────────────────────────────────────────

    private SkillInfo toSkillInfo(SkillRecord r) {
        LinkedHashMap<String, SkillConfig> versions = versionHistory.get(r.id());
        List<String> versionList = versions == null
            ? List.of()
            : new ArrayList<>(versions.keySet());

        return new SkillInfo(
            r.id(), r.tenantId(), r.name(), r.description(),
            r.scope(), r.type(), r.enabled(),
            r.currentVersion(), r.createdAt(), r.updatedAt(),
            versionList
        );
    }
}
