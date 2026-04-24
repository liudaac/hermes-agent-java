package com.nousresearch.hermes.dashboard.handlers;

import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Handler for tool-related API endpoints.
 */
public class ToolsHandler {
    private static final Logger logger = LoggerFactory.getLogger(ToolsHandler.class);

    public ToolsHandler() {
    }

    /**
     * GET /api/tools - Get list of available tools
     */
    public void getTools(Context ctx) {
        List<Map<String, Object>> tools = new ArrayList<>();

        tools.add(createTool("terminal", "Execute terminal commands", "💻", List.of("execute_bash", "execute_command")));
        tools.add(createTool("web_search", "Search the web", "🔍", List.of("web_search", "web_extract")));
        tools.add(createTool("file_operations", "Read and write files", "📁", List.of("file_read", "file_write", "file_search", "file_list")));
        tools.add(createTool("browser", "Browse websites", "🌐", List.of("browser_open", "browser_click", "browser_type", "browser_screenshot")));
        tools.add(createTool("image_generation", "Generate images", "🎨", List.of("image_generate")));
        tools.add(createTool("code_execution", "Execute code", "⚡", List.of("execute_python", "execute_javascript")));
        tools.add(createTool("git", "Git version control", "📊", List.of("git_status", "git_commit", "git_push", "git_pull")));
        tools.add(createTool("memory", "Store and retrieve memories", "🧠", List.of("memory_search", "memory_save")));
        tools.add(createTool("sub_agent", "Spawn sub-agents", "🤖", List.of("subagent_spawn", "subagent_list")));
        tools.add(createTool("tts", "Text to speech", "🔊", List.of("tts_speak")));
        tools.add(createTool("vision", "Vision analysis", "👁️", List.of("vision_analyze")));

        ctx.json(tools);
    }

    /**
     * GET /api/tools/toolsets - Get toolsets
     */
    public void getToolsets(Context ctx) {
        List<Map<String, Object>> toolsets = new ArrayList<>();

        toolsets.add(createToolset("default", "Default", "Core tools enabled by default", true, true,
            List.of("terminal", "web_search", "file_operations", "browser", "code_execution", "git", "memory")));

        toolsets.add(createToolset("web", "Web Tools", "Web browsing and search", true, true,
            List.of("web_search", "browser")));

        toolsets.add(createToolset("development", "Development", "Code execution and version control", true, true,
            List.of("code_execution", "git", "terminal")));

        toolsets.add(createToolset("ai", "AI Tools", "Image generation and vision", false, false,
            List.of("image_generation", "vision", "tts")));

        toolsets.add(createToolset("advanced", "Advanced", "Sub-agents and advanced features", false, false,
            List.of("sub_agent")));

        ctx.json(toolsets);
    }

    private Map<String, Object> createTool(String name, String description, String emoji, List<String> tools) {
        Map<String, Object> tool = new HashMap<>();
        tool.put("name", name);
        tool.put("description", description);
        tool.put("emoji", emoji);
        tool.put("tools", tools);
        return tool;
    }

    private Map<String, Object> createToolset(String name, String label, String description,
                                               boolean enabled, boolean configured, List<String> tools) {
        Map<String, Object> toolset = new HashMap<>();
        toolset.put("name", name);
        toolset.put("label", label);
        toolset.put("description", description);
        toolset.put("enabled", enabled);
        toolset.put("configured", configured);
        toolset.put("tools", tools);
        return toolset;
    }
}
