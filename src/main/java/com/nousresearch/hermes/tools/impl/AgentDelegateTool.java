package com.nousresearch.hermes.tools.impl;

import com.nousresearch.hermes.agent.SubAgent;
import com.nousresearch.hermes.agent.SubAgentResult;
import com.nousresearch.hermes.config.HermesConfig;
import com.nousresearch.hermes.harness.AgentTemplate;
import com.nousresearch.hermes.harness.ForkMode;
import com.nousresearch.hermes.model.ModelMessage;
import com.nousresearch.hermes.tools.ToolEntry;
import com.nousresearch.hermes.tools.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Agent-as-tool: delegate work to a specialized sub-agent.
 *
 * <p>Unlike the raw {@code subagent_spawn} tool, this tool uses
 * predefined {@link AgentTemplate} specialists with tuned system
 * prompts, tool whitelists, and fork modes. The parent agent's
 * conversation history is automatically forked according to the
 * template's default fork mode (or overridden by the caller).</p>
 *
 * <h3>Available specialists</h3>
 * <ul>
 *   <li>{@code code_reviewer} - reviews code for bugs, security, style</li>
 *   <li>{@code test_writer} - generates test cases and test code</li>
 *   <li>{@code researcher} - web research and information gathering</li>
 *   <li>{@code analyzer} - data/log analysis with pattern detection</li>
 *   <li>{@code planner} - task decomposition and planning</li>
 * </ul>
 */
public class AgentDelegateTool {
    private static final Logger logger = LoggerFactory.getLogger(AgentDelegateTool.class);

    /** Thread-local parent history - set by AgentLoop before tool dispatch. */
    private static final ThreadLocal<List<ModelMessage>> parentHistory = new ThreadLocal<>();

    /**
     * Set the parent agent's conversation history for the current tool call.
     * Called by the tool dispatcher before invoking agent_delegate.
     */
    public static void setParentHistory(List<ModelMessage> history) {
        parentHistory.set(history);
    }

    /** Clear after tool call. */
    public static void clearParentHistory() {
        parentHistory.remove();
    }

    public static void register(ToolRegistry registry) {
        registry.register(new ToolEntry.Builder()
            .name("agent_delegate")
            .toolset("subagents")
            .schema(Map.of(
                "description", "Delegate a task to a specialized AI agent. The specialist gets relevant context from the current conversation and works independently.",
                "parameters", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "specialist", Map.of(
                            "type", "string",
                            "description", "Type of specialist to invoke: " +
                                String.join(", ", AgentTemplate.availableTemplates()),
                            "enum", new ArrayList<>(AgentTemplate.availableTemplates())
                        ),
                        "task", Map.of(
                            "type", "string",
                            "description", "The specific task to delegate to the specialist"
                        ),
                        "fork_mode", Map.of(
                            "type", "string",
                            "description", "How much context to share: full (all history), compressed (summarized), clean (no history)",
                            "enum", List.of("full", "compressed", "clean"),
                            "default", "auto"
                        )
                    ),
                    "required", List.of("specialist", "task")
                )
            ))
            .handler(AgentDelegateTool::delegate)
            .emoji("🎯")
            .risk(com.nousresearch.hermes.approval.ToolRisk.LOW)
            .requiresApproval(false)
            .build());
    }

    @SuppressWarnings("unchecked")
    private static String delegate(Map<String, Object> args) {
        String specialistName = (String) args.get("specialist");
        String task = (String) args.get("task");
        String forkModeStr = (String) args.getOrDefault("fork_mode", "auto");

        if (specialistName == null || specialistName.isBlank()) {
            return ToolRegistry.toolError("specialist is required");
        }
        if (task == null || task.isBlank()) {
            return ToolRegistry.toolError("task is required");
        }

        AgentTemplate template = AgentTemplate.find(specialistName);
        if (template == null) {
            return ToolRegistry.toolError(
                "Unknown specialist: '" + specialistName + "'. Available: " +
                String.join(", ", AgentTemplate.availableTemplates()));
        }

        // Resolve fork mode
        ForkMode forkMode;
        if ("auto".equalsIgnoreCase(forkModeStr)) {
            forkMode = template.defaultForkMode();
        } else {
            try {
                forkMode = ForkMode.valueOf(forkModeStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                forkMode = template.defaultForkMode();
            }
        }

        try {
            HermesConfig config = HermesConfig.load();

            SubAgent agent = new SubAgent(task, "", config,
                new com.nousresearch.hermes.model.ModelClient(config.getModelConfig()));

            // Apply template configuration
            agent.withSystemPrompt(template.systemPrompt());
            agent.withToolWhitelist(template.toolWhitelist());
            agent.withMaxIterations(template.maxIterations());

            // Fork parent history if available
            List<ModelMessage> history = parentHistory.get();
            if (history != null && forkMode != ForkMode.CLEAN) {
                agent.forkFrom(new ArrayList<>(history), forkMode);
            }

            logger.info("Delegating to {} specialist (fork={}, task='{}')",
                specialistName, forkMode, task.substring(0, Math.min(60, task.length())));

            SubAgentResult result = agent.call();

            return ToolRegistry.toolResult(Map.of(
                "specialist", specialistName,
                "task", task,
                "output", result.output != null ? result.output : "",
                "success", result.success,
                "completed", result.completed,
                "iterations", result.iterationsUsed,
                "duration_ms", result.durationMs,
                "insights", result.insights != null ? result.insights : List.of(),
                "error", result.error != null ? result.error : ""
            ));

        } catch (Exception e) {
            logger.error("agent_delegate failed: {}", e.getMessage(), e);
            return ToolRegistry.toolError("Delegation failed: " + e.getMessage());
        }
    }
}
