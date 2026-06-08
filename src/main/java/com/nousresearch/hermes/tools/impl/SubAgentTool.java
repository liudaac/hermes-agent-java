package com.nousresearch.hermes.tools.impl;

import com.nousresearch.hermes.agent.SubAgent;
import com.nousresearch.hermes.agent.SubAgentResult;
import com.nousresearch.hermes.collaboration.TaskOrchestrator;
import com.nousresearch.hermes.collaboration.TenantBus;
import com.nousresearch.hermes.config.HermesConfig;
import com.nousresearch.hermes.tools.ToolEntry;
import com.nousresearch.hermes.tools.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Sub-agent delegation tools.
 * Spawn parallel sub-agents for complex tasks.
 * Supports DAG pipeline orchestration via TaskOrchestrator.
 */
public class SubAgentTool {
    private static final Logger logger = LoggerFactory.getLogger(SubAgentTool.class);
    
    public static void register(ToolRegistry registry) {
        // subagent_spawn
        registry.register(new ToolEntry.Builder()
            .name("subagent_spawn")
            .toolset("subagents")
            .schema(Map.of(
                "description", "Spawn a sub-agent to work on a specific task",
                "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "task", Map.of("type", "string", "description", "Task description for the sub-agent"),
                        "context", Map.of("type", "string", "description", "Additional context for the sub-agent")
                    ),
                    "required", List.of("task")
                )
            ))
            .handler(SubAgentTool::spawnSubAgent)
            .emoji("🤖")
            .risk(com.nousresearch.hermes.approval.ToolRisk.LOW)
            .requiresApproval(false)
            .build());
        
        // subagent_spawn_parallel
        registry.register(new ToolEntry.Builder()
            .name("subagent_spawn_parallel")
            .toolset("subagents")
            .schema(Map.of(
                "description", "Spawn multiple sub-agents in parallel",
                "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "tasks", Map.of("type", "array", "items", Map.of("type", "string"), "description", "List of tasks"),
                        "context", Map.of("type", "string", "description", "Shared context for all sub-agents"),
                        "timeout", Map.of("type", "integer", "description", "Timeout in seconds per sub-agent", "default", 120)
                    ),
                    "required", List.of("tasks")
                )
            ))
            .handler(SubAgentTool::spawnParallel)
            .emoji("🤖🤖")
            .risk(com.nousresearch.hermes.approval.ToolRisk.LOW)
            .requiresApproval(false)
            .build());
        
        // subagent_pipeline — DAG orchestration for multi-step workflows
        registry.register(new ToolEntry.Builder()
            .name("subagent_pipeline")
            .toolset("subagents")
            .schema(Map.of(
                "description", "Execute a multi-step pipeline of sub-agent tasks with DAG dependencies",
                "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "steps", Map.of(
                            "type", "array",
                            "items", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                    "name", Map.of("type", "string", "description", "Unique step name"),
                                    "task", Map.of("type", "string", "description", "Task description"),
                                    "depends_on", Map.of("type", "array", "items", Map.of("type", "string"), "description", "Names of steps this depends on"),
                                    "timeout_seconds", Map.of("type", "integer", "description", "Timeout in seconds per step", "default", 120)
                                ),
                                "required", List.of("name", "task")
                            ),
                            "description", "Ordered list of pipeline steps"
                        ),
                        "context", Map.of("type", "string", "description", "Shared context for all steps")
                    ),
                    "required", List.of("steps")
                )
            ))
            .handler(SubAgentTool::runPipeline)
            .emoji("🔗")
            .risk(com.nousresearch.hermes.approval.ToolRisk.MEDIUM)
            .requiresApproval(false)
            .build());
    }
    
    private static String spawnSubAgent(Map<String, Object> args) {
        String task = (String) args.get("task");
        String context = (String) args.getOrDefault("context", "");
        if (task == null || task.trim().isEmpty()) {
            return ToolRegistry.toolError("Task is required");
        }
        try {
            logger.info("Spawning sub-agent for task: {}", task.substring(0, Math.min(50, task.length())));
            HermesConfig config = HermesConfig.load();
            SubAgent agent = new SubAgent(task, context, config);
            SubAgentResult result = agent.call();
            return ToolRegistry.toolResult(Map.of(
                "id", result.id, "task", result.task, "output", result.output,
                "success", result.success, "completed", result.completed,
                "iterations", result.iterationsUsed, "duration_ms", result.durationMs,
                "error", result.error != null ? result.error : ""
            ));
        } catch (Exception e) {
            logger.error("Failed to spawn sub-agent: {}", e.getMessage(), e);
            return ToolRegistry.toolError("Spawn failed: " + e.getMessage());
        }
    }
    
    @SuppressWarnings("unchecked")
    private static String spawnParallel(Map<String, Object> args) {
        List<String> tasks = (List<String>) args.get("tasks");
        String context = (String) args.getOrDefault("context", "");
        int timeout = args.containsKey("timeout") ? ((Number) args.get("timeout")).intValue() : 120;
        if (tasks == null || tasks.isEmpty()) return ToolRegistry.toolError("Tasks list is required");
        if (tasks.size() > 10) return ToolRegistry.toolError("Maximum 10 parallel sub-agents allowed");
        try {
            logger.info("Spawning {} parallel sub-agents", tasks.size());
            HermesConfig config = HermesConfig.load();
            long timeoutMs = timeout * 1000L;
            List<SubAgentResult> results = SubAgent.spawnParallel(tasks, context, config, timeoutMs);
            List<Map<String, Object>> formatted = new ArrayList<>();
            for (SubAgentResult r : results) {
                Map<String, Object> map = new HashMap<>();
                map.put("id", r.id != null ? r.id : "unknown");
                map.put("task", r.task != null ? r.task : "");
                map.put("output", r.output != null ? r.output : "");
                map.put("success", r.success); map.put("completed", r.completed);
                map.put("iterations", r.iterationsUsed); map.put("duration_ms", r.durationMs);
                map.put("error", r.error != null ? r.error : "");
                formatted.add(map);
            }
            long successCount = results.stream().filter(r -> r.success).count();
            return ToolRegistry.toolResult(Map.of(
                "results", formatted, "total", results.size(),
                "successful", successCount, "failed", results.size() - successCount
            ));
        } catch (Exception e) {
            logger.error("Failed to spawn parallel sub-agents: {}", e.getMessage(), e);
            return ToolRegistry.toolError("Parallel spawn failed: " + e.getMessage());
        }
    }
    
    /**
     * Run a DAG pipeline of sub-agent tasks using TaskOrchestrator.
     */
    @SuppressWarnings("unchecked")
    private static String runPipeline(Map<String, Object> args) {
        List<Map<String, Object>> stepDefs = (List<Map<String, Object>>) args.get("steps");
        String context = (String) args.getOrDefault("context", "");
        if (stepDefs == null || stepDefs.isEmpty()) return ToolRegistry.toolError("Steps list is required");
        try {
            TaskOrchestrator orchestrator = new TaskOrchestrator(TenantBus.getInstance());
            List<TaskOrchestrator.Step> steps = new ArrayList<>();
            for (Map<String, Object> def : stepDefs) {
                String name = (String) def.get("name");
                String task = (String) def.get("task");
                long timeoutSec = def.containsKey("timeout_seconds") ? ((Number) def.get("timeout_seconds")).longValue() : 120;
                TaskOrchestrator.Step step = new TaskOrchestrator.Step(name, "subagent_" + name, task);
                step.payload("task", task); step.payload("context", context);
                step.timeoutMs(timeoutSec * 1000);
                List<String> deps = (List<String>) def.get("depends_on");
                if (deps != null && !deps.isEmpty()) step.dependsOn(deps.toArray(new String[0]));
                steps.add(step);
            }
            TaskOrchestrator.Pipeline pipeline = orchestrator.orchestrate("pipeline_" + System.currentTimeMillis(), steps);
            long deadline = System.currentTimeMillis() + 600_000;
            while (pipeline.status == TaskOrchestrator.Pipeline.Status.PENDING
                   || pipeline.status == TaskOrchestrator.Pipeline.Status.RUNNING) {
                if (System.currentTimeMillis() > deadline) break;
                Thread.sleep(500);
            }
            List<Map<String, Object>> stepResults = new ArrayList<>();
            int succeeded = 0, failed = 0;
            for (TaskOrchestrator.StepResult sr : pipeline.results.values()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", sr.name); m.put("success", sr.success);
                m.put("output", sr.error != null ? "" : (sr.output != null ? sr.output.toString() : ""));
                m.put("error", sr.error != null ? sr.error : "");
                stepResults.add(m);
                if (sr.success) succeeded++; else failed++;
            }
            return ToolRegistry.toolResult(Map.of(
                "pipeline_id", pipeline.id, "status", pipeline.status.name(),
                "progress", pipeline.progress(), "steps", stepResults,
                "total", stepResults.size(), "succeeded", succeeded, "failed", failed,
                "errors", pipeline.errors != null ? pipeline.errors : List.of()
            ));
        } catch (Exception e) {
            logger.error("Pipeline execution failed: {}", e.getMessage(), e);
            return ToolRegistry.toolError("Pipeline failed: " + e.getMessage());
        }
    }
}