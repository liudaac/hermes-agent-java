package com.nousresearch.hermes.tools.impl;

import com.nousresearch.hermes.agent.SubAgent;
import com.nousresearch.hermes.config.HermesConfig;
import com.nousresearch.hermes.tools.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Sub-agent delegation tools.
 * Spawn parallel sub-agents for complex tasks.
 */
public class SubAgentTool {
    private static final Logger logger = LoggerFactory.getLogger(SubAgentTool.class);
    
    /**
     * Register sub-agent tools.
     */
    public static void register(ToolRegistry registry) {
        // subagent_spawn
        registry.register(new ToolRegistry.Builder()
            .name("subagent_spawn")
            .toolset("subagents")
            .schema(Map.of(
                "description", "Spawn a sub-agent to work on a specific task",
                "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "task", Map.of(
                            "type", "string",
                            "description", "Task description for the sub-agent"
                        ),
                        "context", Map.of(
                            "type", "string",
                            "description", "Additional context for the sub-agent"
                        )
                    ),
                    "required", List.of("task")
                )
            ))
            .handler(SubAgentTool::spawnSubAgent)
            .emoji("🤖")
            .build());
        
        // subagent_spawn_parallel
        registry.register(new ToolRegistry.Builder()
            .name("subagent_spawn_parallel")
            .toolset("subagents")
            .schema(Map.of(
                "description", "Spawn multiple sub-agents in parallel",
                "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "tasks", Map.of(
                            "type", "array",
                            "items", Map.of("type", "string"),
                            "description", "List of tasks"
                        ),
                        "context", Map.of(
                            "type", "string",
                            "description", "Shared context for all sub-agents"
                        ),
                        "timeout", Map.of(
                            "type", "integer",
                            "description", "Timeout in seconds per sub-agent",
                            "default", 120
                        )
                    ),
                    "required", List.of("tasks")
                )
            ))
            .handler(SubAgentTool::spawnParallel)
            .emoji("🤖🤖")
            .build());
    }
    
    /**
     * Spawn a single sub-agent.
     */
    private static String spawnSubAgent(Map<String, Object> args) {
        String task = (String) args.get("task");
        String context = (String) args.getOrDefault("context", "");
        
        if (task == null || task.trim().isEmpty()) {
            return ToolRegistry.toolError("Task is required");
        }
        
        try {
            logger.info("Spawning sub-agent for task: {}", task.substring(0, Math.min(50, task.length())));
            
            // Create and run sub-agent
            HermesConfig config = HermesConfig.load();
            SubAgent agent = new SubAgent(task, context, config);
            SubAgent.SubAgentResult result = agent.call();
            
            return ToolRegistry.toolResult(Map.of(
                "id", result.id,
                "task", result.task,
                "output", result.output,
                "success", result.success,
                "completed", result.completed,
                "iterations", result.iterationsUsed,
                "duration_ms", result.durationMs,
                "error", result.error != null ? result.error : ""
            ));
            
        } catch (Exception e) {
            logger.error("Failed to spawn sub-agent: {}", e.getMessage(), e);
            return ToolRegistry.toolError("Spawn failed: " + e.getMessage());
        }
    }
    
    /**
     * Spawn multiple sub-agents in parallel.
     */
    @SuppressWarnings("unchecked")
    private static String spawnParallel(Map<String, Object> args) {
        List<String> tasks = (List<String>) args.get("tasks");
        String context = (String) args.getOrDefault("context", "");
        int timeout = args.containsKey("timeout") ? ((Number) args.get("timeout")).intValue() : 120;
        
        if (tasks == null || tasks.isEmpty()) {
            return ToolRegistry.toolError("Tasks list is required");
        }
        
        if (tasks.size() > 10) {
            return ToolRegistry.toolError("Maximum 10 parallel sub-agents allowed");
        }
        
        try {
            logger.info("Spawning {} parallel sub-agents", tasks.size());
            
            HermesConfig config = HermesConfig.load();
            long timeoutMs = timeout * 1000L;
            
            List<SubAgent.SubAgentResult> results = SubAgent.spawnParallel(tasks, context, config, timeoutMs);
            
            List<Map<String, Object>> formatted = results.stream()
                .map(r -> Map.of(
                    "id", r.id != null ? r.id : "unknown",
                    "task", r.task != null ? r.task : "",
                    "output", r.output != null ? r.output : "",
                    "success", r.success,
                    "completed", r.completed,
                    "iterations", r.iterationsUsed,
                    "duration_ms", r.durationMs,
                    "error", r.error != null ? r.error : ""
                ))
                .collect(Collectors.toList());
            
            long successCount = results.stream().filter(r -> r.success).count();
            
            return ToolRegistry.toolResult(Map.of(
                "results", formatted,
                "total", results.size(),
                "successful", successCount,
                "failed", results.size() - successCount
            ));
            
        } catch (Exception e) {
            logger.error("Failed to spawn parallel sub-agents: {}", e.getMessage(), e);
            return ToolRegistry.toolError("Parallel spawn failed: " + e.getMessage());
        }
    }
}
