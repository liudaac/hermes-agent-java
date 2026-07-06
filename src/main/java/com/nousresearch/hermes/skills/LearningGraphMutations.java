package com.nousresearch.hermes.skills;

import com.nousresearch.hermes.memory.MemoryManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * User-initiated edit/delete for learning graph nodes (skills + memories).
 *
 * <p>Aligned with the original Python Hermes {@code agent/learning_mutations.py}.
 * Maps a node id back to its on-disk home and performs the mutation.</p>
 *
 * <p>Node id conventions:</p>
 * <ul>
 *   <li><b>skills</b> → the skill name (e.g. {@code "debugging-hermes-desktop"})</li>
 *   <li><b>memories</b> → {@code memory:<source>:<index>} where source is
 *       {@code memory} (MEMORY.md) or {@code user} (USER.md) and index is
 *       the node's position in the combined card list (MEMORY.md cards first,
 *       then USER.md).</li>
 * </ul>
 *
 * <p>Deleting a skill <b>archives</b> it (recoverable via
 * {@code /curator restore}); deleting a memory removes it from the memory
 * store. Editing a skill patches its SKILL.md; editing a memory rewrites
 * the memory chunk.</p>
 */
public class LearningGraphMutations {
    private static final Logger logger = LoggerFactory.getLogger(LearningGraphMutations.class);

    private final SkillManager skillManager;
    private final MemoryManager memoryManager;

    /** Memory sources that map to MemoryManager categories. */
    private static final Map<String, String> MEMORY_SOURCE_TO_CATEGORY = Map.of(
        "memory", "memory",
        "user", "user"
    );

    public LearningGraphMutations(SkillManager skillManager) {
        this(skillManager, null);
    }

    public LearningGraphMutations(SkillManager skillManager, MemoryManager memoryManager) {
        this.skillManager = skillManager;
        this.memoryManager = memoryManager;
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
        ParsedMemoryId parsed = parseMemoryId(nodeId);
        if (parsed == null) {
            return Map.of("ok", false, "message", "Invalid memory node id: " + nodeId);
        }

        if (memoryManager == null) {
            return Map.of("ok", false, "message",
                "MemoryManager not available — use /memory commands instead");
        }

        List<String> entries = memoryManager.getByCategory(parsed.category, Integer.MAX_VALUE);
        if (parsed.index < 0 || parsed.index >= entries.size()) {
            return Map.of("ok", false, "message",
                "Memory index " + parsed.index + " out of range (have " + entries.size() + " entries)");
        }

        String body = entries.get(parsed.index);
        String label = body.split("\n", 2)[0];
        if (label.length() > 80) label = label.substring(0, 77) + "…";

        return Map.of(
            "ok", true,
            "kind", "memory",
            "id", nodeId,
            "label", label,
            "content", body
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
        SkillManager.Skill skill = skillManager.loadSkill(name);
        if (skill == null) {
            return Map.of("ok", false, "message", "Skill '" + name + "' not found");
        }
        if (skill.pinned) {
            return Map.of("ok", false, "message",
                "'" + name + "' is pinned — unpin it first (/journey unpin " + name + ")");
        }

        boolean archived = skillManager.archiveSkill(name);
        if (archived) {
            return Map.of("ok", true, "message",
                "archived '" + name + "' — restore with: /curator restore " + name);
        }
        return Map.of("ok", false, "message", "Failed to archive '" + name + "'");
    }

    private Map<String, Object> deleteMemory(String nodeId) {
        ParsedMemoryId parsed = parseMemoryId(nodeId);
        if (parsed == null) {
            return Map.of("ok", false, "message", "Invalid memory node id: " + nodeId);
        }

        if (memoryManager == null) {
            return Map.of("ok", false, "message",
                "MemoryManager not available — use /memory delete instead");
        }

        List<String> entries = memoryManager.getByCategory(parsed.category, Integer.MAX_VALUE);
        if (parsed.index < 0 || parsed.index >= entries.size()) {
            return Map.of("ok", false, "message",
                "Memory index " + parsed.index + " out of range");
        }

        String entry = entries.get(parsed.index);
        // Use a unique substring from the entry for deletion
        String substring = entry.length() > 50 ? entry.substring(0, 50) : entry;
        boolean deleted = memoryManager.delete(parsed.category, substring);
        if (deleted) {
            return Map.of("ok", true, "message",
                "deleted memory from " + parsed.source + " (" + parsed.category + ")");
        }
        return Map.of("ok", false, "message", "Failed to delete memory — entry may have changed");
    }

    // ── Edit ────────────────────────────────────────────────────────────

    /**
     * Edit a node's content (update SKILL.md for skills, replace for memories).
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

        ParsedMemoryId parsed = parseMemoryId(nodeId);
        if (parsed == null) {
            return Map.of("ok", false, "message", "Invalid memory node id: " + nodeId);
        }

        if (memoryManager == null) {
            return Map.of("ok", false, "message",
                "MemoryManager not available — use /memory commands instead");
        }

        List<String> entries = memoryManager.getByCategory(parsed.category, Integer.MAX_VALUE);
        if (parsed.index < 0 || parsed.index >= entries.size()) {
            return Map.of("ok", false, "message",
                "Memory index " + parsed.index + " out of range");
        }

        String oldEntry = entries.get(parsed.index);
        String oldSubstring = oldEntry.length() > 50 ? oldEntry.substring(0, 50) : oldEntry;
        boolean replaced = memoryManager.replace(parsed.category, oldSubstring, content.trim());
        if (replaced) {
            return Map.of("ok", true, "message",
                "updated memory in " + parsed.source + " (" + parsed.category + ")");
        }
        return Map.of("ok", false, "message", "Failed to update memory — entry may have changed");
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

    // ── Memory id parsing ───────────────────────────────────────────────

    /**
     * Parse a memory node id: memory:<source>:<index>
     *
     * <p>Global index: MEMORY.md cards first, then USER.md cards.
     * If source is "user", the local index = global - memory_count.</p>
     */
    private ParsedMemoryId parseMemoryId(String nodeId) {
        if (nodeId == null) return null;
        String[] parts = nodeId.split(":", 3);
        if (parts.length != 3 || !"memory".equals(parts[0])) {
            return null;
        }
        String source = parts[1];
        String category = MEMORY_SOURCE_TO_CATEGORY.get(source);
        if (category == null) {
            return null;
        }
        int globalIndex;
        try {
            globalIndex = Integer.parseInt(parts[2]);
        } catch (NumberFormatException e) {
            return null;
        }

        // If memoryManager is available, convert global index to local
        if (memoryManager != null) {
            int memoryCount = memoryManager.getByCategory("memory", Integer.MAX_VALUE).size();
            int localIndex;
            if ("memory".equals(source)) {
                localIndex = globalIndex;
            } else {
                localIndex = globalIndex - memoryCount;
            }
            if (localIndex < 0) {
                return null;
            }
            return new ParsedMemoryId(source, category, globalIndex, localIndex);
        }

        // No memoryManager — use global as local
        return new ParsedMemoryId(source, category, globalIndex, globalIndex);
    }

    private static class ParsedMemoryId {
        final String source;
        final String category;
        final int globalIndex;
        final int index; // local index within the source's own file

        ParsedMemoryId(String source, String category, int globalIndex, int index) {
            this.source = source;
            this.category = category;
            this.globalIndex = globalIndex;
            this.index = index;
        }
    }
}
