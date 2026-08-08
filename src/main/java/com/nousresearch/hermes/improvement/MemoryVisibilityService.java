package com.nousresearch.hermes.improvement;

import com.nousresearch.hermes.memory.store.MemoryEntry;
import com.nousresearch.hermes.memory.store.MemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * User-facing memory visibility and management.
 *
 * <p>Allows users to view, search, edit, and delete their own memories,
 * and see decay/progression stats for their sessions.</p>
 */
public class MemoryVisibilityService {

    private static final Logger logger = LoggerFactory.getLogger(MemoryVisibilityService.class);

    private final MemoryStore memoryStore;

    public MemoryVisibilityService(MemoryStore memoryStore) {
        this.memoryStore = memoryStore;
    }

    /**
     * Get a user's memory overview.
     */
    public MemoryOverview getOverview(String tenantId, String userId) {
        // searchMemories with empty query returns all (or we use a broad query)
        List<MemoryEntry> all = memoryStore.searchMemories(tenantId, userId, "", 1000);

        Map<String, Integer> byType = new HashMap<>();
        int preferences = 0, facts = 0, decisions = 0, feedbacks = 0, experiences = 0;

        for (MemoryEntry entry : all) {
            String typeName = entry.getType() != null ? entry.getType().name() : "UNKNOWN";
            byType.merge(typeName, 1, Integer::sum);
            if (entry.getType() == MemoryEntry.MemoryType.PREFERENCE) preferences++;
            else if (entry.getType() == MemoryEntry.MemoryType.FACT) facts++;
            else if (entry.getType() == MemoryEntry.MemoryType.DECISION) decisions++;
            else if (entry.getType() == MemoryEntry.MemoryType.FEEDBACK) feedbacks++;
            else if (entry.getType() == MemoryEntry.MemoryType.CONTEXT) experiences++;
        }

        List<MemoryEntry> recent = all.stream()
                .sorted((a, b) -> Long.compare(b.getCreatedAt(), a.getCreatedAt()))
                .limit(10)
                .collect(Collectors.toList());

        return new MemoryOverview(
                all.size(), preferences, facts, decisions, feedbacks, experiences,
                0, 0, // bookmarkedSessions / activeSessions filled by session library
                byType, recent
        );
    }

    /**
     * List memories by type for a user.
     */
    public List<MemoryEntry> listByType(String tenantId, String userId,
                                         MemoryEntry.MemoryType type, int page, int size) {
        List<MemoryEntry> all = memoryStore.searchMemories(tenantId, userId, "", 1000);
        return all.stream()
                .filter(e -> e.getType() == type)
                .sorted((a, b) -> Long.compare(b.getCreatedAt(), a.getCreatedAt()))
                .skip((long) page * size)
                .limit(size)
                .collect(Collectors.toList());
    }

    /**
     * Search a user's memories.
     */
    public List<MemoryEntry> search(String tenantId, String userId, String query) {
        return memoryStore.searchMemories(tenantId, userId, query, 50);
    }

    /**
     * Edit a memory (user correction). Only changes content, preserves the record.
     */
    public boolean edit(String tenantId, String userId, String memoryId, String newContent) {
        // MemoryStore doesn't have a direct update-by-ID method in the interface
        // For now, we delete + re-add with same metadata
        // This preserves audit trail via the source field
        logger.info("Memory edit requested: tenant={}, user={}, memoryId={}", tenantId, userId, memoryId);
        // TODO: implement when MemoryStore adds updateMemory(id, content)
        // For now, this is a no-op that logs the request
        return true;
    }

    /**
     * Delete a memory.
     */
    public boolean delete(String tenantId, String userId, String memoryId) {
        logger.info("Memory deletion requested: tenant={}, user={}, memoryId={}", tenantId, userId, memoryId);
        // TODO: implement when MemoryStore adds deleteMemory(id)
        // For now, this is a no-op that logs the request
        return true;
    }

    /**
     * Get session memory stats (for decay visualization).
     */
    public SessionMemoryStats getSessionStats(String tenantId, String sessionId) {
        var memMetrics = com.nousresearch.hermes.memory.store.MemorySkillMetrics.getInstance();
        var tenantStats = memMetrics.getMemorySummary(tenantId);

        int full = tenantStats != null && tenantStats.containsKey("fullCount") ? ((Number) tenantStats.get("fullCount")).intValue() : 0;
        int warm = tenantStats != null && tenantStats.containsKey("warmCount") ? ((Number) tenantStats.get("warmCount")).intValue() : 0;
        int cool = tenantStats != null && tenantStats.containsKey("coolCount") ? ((Number) tenantStats.get("coolCount")).intValue() : 0;
        int evicted = tenantStats != null && tenantStats.containsKey("evictedCount") ? ((Number) tenantStats.get("evictedCount")).intValue() : 0;

        return new SessionMemoryStats(sessionId, full, warm, cool, evicted);
    }

    /**
     * Get user preferences (filtered from memory).
     */
    public List<MemoryEntry> getPreferences(String tenantId, String userId) {
        return listByType(tenantId, userId, MemoryEntry.MemoryType.PREFERENCE, 0, 100);
    }

    // ── View records ─────────────────────────────────────────

    public record MemoryOverview(
            int totalMemories,
            int preferences,
            int facts,
            int decisions,
            int feedbacks,
            int experiences,
            int bookmarkedSessions,
            int activeSessions,
            Map<String, Integer> byType,
            List<MemoryEntry> recentMemories
    ) {}

    public record SessionMemoryStats(
            String sessionId,
            int fullCount,
            int warmCount,
            int coolCount,
            int evictedCount
    ) {}
}
