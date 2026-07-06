package com.nousresearch.hermes.skills;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * User-initiated edit/delete for learning graph nodes (skills + memories).
 *
 * <p>Aligned with the original Python Hermes {@code agent/learning_mutations.py}.
 * Maps a node id back to its on-disk home and performs the mutation.</p>
 *
 * <p>Shared by the {@code /journey} command, the dashboard REST API, and
 * the TUI overlay.</p>
 *
 * <p>Deleting a skill <b>archives</b> it (recoverable via
 * {@code /curator restore}); deleting a memory removes it from the memory
 * store. Editing a skill patches its SKILL.md; editing a memory rewrites
 * the memory chunk.</p>
 */
public class LearningGraphMutations {
    private static final Logger logger = LoggerFactory.getLogger(LearningGraphMutations.class);

    private final SkillManager skillManager;

    public LearningGraphMutations(SkillManager skillManager) {
        this.skillManager = skillManager;
    }

    /**
     * Parse the node kind from a node id.
     *
     * @return "memory" or "skill"
     */
    public static String parseNodeKind(String nodeId) {
        return nodeId != null && nodeId.startsWith("memory:") ? "memory" : "skill";
    }

    // ── Inspect (edit prefill) ──────────────────────────────────────────

    /**
     * Get current content for an edit prefill.
     *
     * @param nodeId skill name or memory:<source>:<index>
     * @return map with ok, kind, id, label, content
     */
    public Map<String, Object> nodeDetail(String nodeId) {
        try {
            if ("memory".equals(parseNodeKind(nodeId))) {
                return memoryNodeDetail(nodeId);
            }
            return skillNodeDetail(nodeId);
        } catch (Exception e) {
            return Map.of("ok", false, "message", e.getMessage());
        }
    }

    private Map<String, Object> skillNodeDetail(String name) {
        SkillManager.Skill skill = skillManager.loadSkill(name);
        if (skill == null) {
            return Map.of("ok", false, "message", "Skill '" + name + "' not found");
        }
        return Map.of(
            "ok", true,
            "kind", "skill",
            "id", name,
            "label", name,
            "content", skill.content != null ? skill.content : ""
        );
    }

    private Map<String, Object> memoryNodeDetail(String nodeId) {
        // Memory nodes: memory:<source>:<index>
        // For now, return the raw content from memory manager if available
        // Full implementation requires MemoryManager integration
        return Map.of(
            "ok", false,
            "message", "Memory node detail requires MemoryManager integration — use /memory commands instead"
        );
    }

    // ── Delete ──────────────────────────────────────────────────────────

    /**
     * Delete a node (archive for skills, remove for memories).
     *
     * @param nodeId skill name or memory:<source>:<index>
     * @return map with ok, message
     */
    public Map<String, Object> deleteNode(String nodeId) {
        try {
            if ("memory".equals(parseNodeKind(nodeId))) {
                return deleteMemory(nodeId);
            }
            return deleteSkill(nodeId);
        } catch (Exception e) {
            return Map.of("ok", false, "message", e.getMessage());
        }
    }

    private Map<String, Object> deleteSkill(String name) {
        // Check if pinned
        SkillManager.Skill skill = skillManager.loadSkill(name);
        if (skill == null) {
            return Map.of("ok", false, "message", "Skill '" + name + "' not found");
        }
        if (skill.pinned) {
            return Map.of("ok", false, "message",
                "'" + name + "' is pinned — unpin it first (/curator unpin " + name + ")");
        }

        boolean archived = skillManager.archiveSkill(name);
        if (archived) {
            return Map.of("ok", true, "message",
                "archived '" + name + "' — restore with: /curator restore " + name);
        }
        return Map.of("ok", false, "message", "Failed to archive '" + name + "'");
    }

    private Map<String, Object> deleteMemory(String nodeId) {
        // Memory deletion requires MemoryManager integration
        return Map.of("ok", false, "message",
            "Memory deletion requires MemoryManager — use /memory delete instead");
    }

    // ── Edit ────────────────────────────────────────────────────────────

    /**
     * Edit a node's content (patch SKILL.md for skills, rewrite for memories).
     *
     * @param nodeId  skill name or memory:<source>:<index>
     * @param content new content
     * @return map with ok, message
     */
    public Map<String, Object> editNode(String nodeId, String content) {
        try {
            if ("memory".equals(parseNodeKind(nodeId))) {
                return editMemory(nodeId, content);
            }
            return editSkill(nodeId, content);
        } catch (Exception e) {
            return Map.of("ok", false, "message", e.getMessage());
        }
    }

    private Map<String, Object> editSkill(String name, String content) {
        if (content == null || content.isBlank()) {
            return Map.of("ok", false, "message", "empty content — use delete to remove");
        }
        boolean success = skillManager.updateSkill(name, content, "journey edit");
        if (success) {
            return Map.of("ok", true, "message", "updated '" + name + "'");
        }
        return Map.of("ok", false, "message", "Failed to update '" + name + "' — skill not found");
    }

    private Map<String, Object> editMemory(String nodeId, String content) {
        if (content == null || content.isBlank()) {
            return Map.of("ok", false, "message", "empty memory — use delete to remove it");
        }
        // Memory editing requires MemoryManager integration
        return Map.of("ok", false, "message",
            "Memory editing requires MemoryManager — use /memory commands instead");
    }

    // ── Pin / Unpin ─────────────────────────────────────────────────────

    /**
     * Pin a skill (prevents archive/consolidation).
     */
    public Map<String, Object> pinSkill(String name) {
        SkillManager.Skill skill = skillManager.loadSkill(name);
        if (skill == null) {
            return Map.of("ok", false, "message", "Skill '" + name + "' not found");
        }
        skill.pinned = true;
        return Map.of("ok", true, "message", "pinned '" + name + "'");
    }

    /**
     * Unpin a skill.
     */
    public Map<String, Object> unpinSkill(String name) {
        SkillManager.Skill skill = skillManager.loadSkill(name);
        if (skill == null) {
            return Map.of("ok", false, "message", "Skill '" + name + "' not found");
        }
        skill.pinned = false;
        return Map.of("ok", true, "message", "unpinned '" + name + "'");
    }
}
