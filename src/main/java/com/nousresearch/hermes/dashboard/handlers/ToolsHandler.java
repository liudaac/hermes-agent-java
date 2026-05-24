package com.nousresearch.hermes.dashboard.handlers;

import com.nousresearch.hermes.tools.ToolEntry;
import com.nousresearch.hermes.tools.ToolRegistry;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Handler for tool-related API endpoints.
 */
public class ToolsHandler {
    private static final Logger logger = LoggerFactory.getLogger(ToolsHandler.class);

    private final ToolRegistry toolRegistry;

    public ToolsHandler() {
        this(ToolRegistry.getInstance());
    }

    ToolsHandler(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    /**
     * GET /api/tools - Get registered tools grouped by toolset.
     */
    public void getTools(Context ctx) {
        try {
            ctx.json(buildToolGroups());
        } catch (Exception e) {
            logger.error("Error getting tools: {}", e.getMessage(), e);
            ctx.status(500).result("Error getting tools");
        }
    }

    /**
     * GET /api/tools/toolsets - Get registered toolsets.
     */
    public void getToolsets(Context ctx) {
        try {
            ctx.json(buildToolsets());
        } catch (Exception e) {
            logger.error("Error getting toolsets: {}", e.getMessage(), e);
            ctx.status(500).result("Error getting toolsets");
        }
    }

    List<Map<String, Object>> buildToolGroups() {
        Map<String, List<ToolEntry>> byToolset = toolRegistry.getAllTools().stream()
            .collect(Collectors.groupingBy(ToolEntry::getToolset, TreeMap::new, Collectors.toList()));

        List<Map<String, Object>> groups = new ArrayList<>();
        for (Map.Entry<String, List<ToolEntry>> entry : byToolset.entrySet()) {
            String toolset = entry.getKey();
            List<ToolEntry> tools = entry.getValue().stream()
                .sorted(Comparator.comparing(ToolEntry::getName))
                .toList();

            Map<String, Object> group = new LinkedHashMap<>();
            group.put("name", toolset);
            group.put("description", describeToolset(toolset));
            group.put("emoji", toolsetEmoji(toolset, tools));
            group.put("available", toolRegistry.isToolsetAvailable(toolset));
            group.put("tools", tools.stream().map(ToolEntry::getName).toList());
            group.put("tool_details", tools.stream().map(this::toolToMap).toList());
            group.put("source", "ToolRegistry");
            groups.add(group);
        }
        return groups;
    }

    List<Map<String, Object>> buildToolsets() {
        Map<String, ToolRegistry.ToolsetInfo> toolsets = new TreeMap<>(toolRegistry.getAvailableToolsets());
        List<Map<String, Object>> result = new ArrayList<>();

        for (Map.Entry<String, ToolRegistry.ToolsetInfo> entry : toolsets.entrySet()) {
            String name = entry.getKey();
            ToolRegistry.ToolsetInfo info = entry.getValue();

            Map<String, Object> toolset = new LinkedHashMap<>();
            toolset.put("name", name);
            toolset.put("label", labelForToolset(name));
            toolset.put("description", describeToolset(name));
            toolset.put("enabled", info.available);
            toolset.put("configured", info.available);
            toolset.put("available", info.available);
            toolset.put("requirements", info.requirements);
            toolset.put("tools", info.tools.stream().sorted().toList());
            toolset.put("source", "ToolRegistry");
            result.add(toolset);
        }

        return result;
    }

    private Map<String, Object> toolToMap(ToolEntry entry) {
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("name", entry.getName());
        tool.put("toolset", entry.getToolset());
        tool.put("description", descriptionForTool(entry));
        tool.put("emoji", entry.getEmoji());
        tool.put("async", entry.isAsync());
        tool.put("requires_env", entry.getRequiresEnv());
        tool.put("max_result_size_chars", entry.getMaxResultSizeChars());
        return tool;
    }

    private String descriptionForTool(ToolEntry entry) {
        if (entry.getDescription() != null && !entry.getDescription().isBlank()) {
            return entry.getDescription();
        }
        Object schemaDescription = entry.getSchema() != null ? entry.getSchema().get("description") : null;
        return schemaDescription != null ? schemaDescription.toString() : "Registered tool";
    }

    private String labelForToolset(String toolset) {
        return Arrays.stream(toolset.split("[_-]"))
            .filter(part -> !part.isBlank())
            .map(part -> part.substring(0, 1).toUpperCase(Locale.ROOT) + part.substring(1))
            .collect(Collectors.joining(" "));
    }

    private String describeToolset(String toolset) {
        return switch (toolset) {
            case "file_operations" -> "Filesystem read, write and search tools";
            case "terminal" -> "Shell command execution tools";
            case "web_search" -> "Web search and extraction tools";
            case "browser" -> "Browser automation tools";
            case "code" -> "Code execution tools";
            case "git" -> "Git version control tools";
            case "vision" -> "Vision analysis tools";
            case "tts" -> "Text-to-speech tools";
            case "image" -> "Image generation and editing tools";
            case "cronjob" -> "Scheduled job tools";
            case "homeassistant" -> "Home Assistant control tools";
            case "mcp" -> "MCP server and tool bridge";
            case "subagents" -> "Sub-agent orchestration tools";
            case "rl_training" -> "Reinforcement learning training tools";
            default -> labelForToolset(toolset) + " tools";
        };
    }

    private String toolsetEmoji(String toolset, List<ToolEntry> tools) {
        return switch (toolset) {
            case "file_operations" -> "📁";
            case "terminal" -> "💻";
            case "web_search" -> "🔍";
            case "browser" -> "🌐";
            case "code" -> "⚡";
            case "git" -> "📊";
            case "vision" -> "👁️";
            case "tts" -> "🔊";
            case "image" -> "🎨";
            case "cronjob" -> "⏰";
            case "homeassistant" -> "🏠";
            case "mcp" -> "🔌";
            case "subagents" -> "🤖";
            case "rl_training" -> "🧪";
            default -> tools.stream()
                .map(ToolEntry::getEmoji)
                .filter(emoji -> emoji != null && !emoji.isBlank())
                .findFirst()
                .orElse("⚡");
        };
    }
}
