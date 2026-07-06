package com.nousresearch.hermes.skills;

import com.nousresearch.hermes.plugin.PluginManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Registers the /journey slash command — visualizes the learning timeline.
 *
 * <p>Aligned with the original Python Hermes {@code /journey} CLI command,
 * which opens a timeline view of learned skills and memories.</p>
 *
 * <p>In the Java version, /journey renders an ASCII timeline chart directly
 * in the terminal/chat. The dashboard can consume the same data via
 * {@code /api/learning/graph} REST endpoint.</p>
 */
public class JourneyCommandRegistrar {
    private static final Logger logger = LoggerFactory.getLogger(JourneyCommandRegistrar.class);

    private static SkillManager skillManager;
    private static LearningGraphService graphService;

    public static void register(PluginManager pluginManager, SkillManager sm,
                                  LearningGraphService graph) {
        skillManager = sm;
        graphService = graph;
        pluginManager.trackSlashCommand(
            "journey",
            JourneyCommandRegistrar::handleJourney,
            "View your learning timeline — skills and memories mapped over time",
            "[--json] [--limit N]",
            "hermes-core"
        );
        logger.info("Registered /journey slash command");
    }

    private static Object handleJourney(String input) {
        if (skillManager == null || graphService == null) {
            return errorResult("Learning graph not initialized");
        }

        String args = extractArgs(input);
        boolean asJson = args.contains("--json");

        int limit = 50;
        // Parse --limit N
        String[] parts = args.split("\\s+");
        for (int i = 0; i < parts.length - 1; i++) {
            if ("--limit".equals(parts[i])) {
                try { limit = Integer.parseInt(parts[i + 1]); } catch (NumberFormatException ignored) {}
            }
        }

        // Build the graph from current skills
        graphService.buildFromSkillManager(skillManager);

        if (asJson) {
            // Return JSON payload for programmatic consumption
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("type", "json");
            result.put("data", graphService.toPayload());
            return result;
        }

        // Render ASCII timeline
        var skillNodes = graphService.getSkillNodes().stream()
            .map(JourneyCommandRegistrar::toNodeInfo)
            .toList();

        // Memory nodes (if any were loaded)
        var memoryNodes = graphService.getMemoryNodes().stream()
            .map(JourneyCommandRegistrar::toNodeInfo)
            .toList();

        String chart = LearningGraphRenderer.renderTimeline(skillNodes, memoryNodes, 80);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "message");
        result.put("message", "📊 Learning Timeline\n\n```\n" + chart + "\n```");
        return result;
    }

    private static LearningGraphRenderer.GraphNodeInfo toNodeInfo(LearningGraphService.GraphNode node) {
        var info = new LearningGraphRenderer.GraphNodeInfo();
        info.id = node.id();
        info.label = node.label();
        info.isMemory = node.type() == LearningGraphService.NodeType.MEMORY_CHUNK;
        info.category = node.tags() != null && !node.tags().isEmpty() ? node.tags().get(0) : "skill";
        info.meta = node.content() != null ? node.content() : "";
        // Try to parse timestamp from content or use now
        info.timestamp = java.time.Instant.now();
        return info;
    }

    private static String extractArgs(String input) {
        if (input == null || input.isBlank()) return "";
        String trimmed = input.strip();
        int spaceIdx = trimmed.indexOf(' ');
        if (spaceIdx < 0) return "";
        return trimmed.substring(spaceIdx + 1).strip();
    }

    private static Map<String, Object> errorResult(String msg) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "error");
        result.put("message", msg);
        return result;
    }
}
