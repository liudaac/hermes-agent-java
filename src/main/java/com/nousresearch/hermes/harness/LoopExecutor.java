package com.nousresearch.hermes.harness;

import com.nousresearch.hermes.agent.TenantAwareAIAgent;
import com.nousresearch.hermes.config.Constants;
import com.nousresearch.hermes.model.ChatCompletionResponse;
import com.nousresearch.hermes.model.ModelMessage;
import com.nousresearch.hermes.model.ToolCall;
import com.nousresearch.hermes.plugin.hook.HookType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Bridge that lets {@link TenantAwareAIAgent#doProcessMessage} delegate
 * the core while-loop to {@link AgentLoop}, without breaking the 20+
 * side effects (memory, skills, cognitive trace, etc.) that live in
 * the original method.
 *
 * <p>This is an intermediate step in the slimming process:
 * <ol>
 *   <li>Loop body delegates here (reduces doProcessMessage by ~150 lines)</li>
 *   <li>Loop body further delegates to AgentLoop.run() (reduces by ~200 more)</li>
 *   <li>doProcessMessage becomes a thin orchestrator (~50 lines)</li>
 * </ol></p>
 */
public class LoopExecutor {
    private static final Logger logger = LoggerFactory.getLogger(LoopExecutor.class);

    /**
     * Execute the think->act->observe loop.
     *
     * @param ctx the agent context (borrowed references)
     * @param state mutable loop state
     * @param emitter event emitter (null = no events)
     * @return loop result
     */
    public static LoopResult execute(AgentContext ctx, LoopState state, EventEmitter emitter) {
        state.setLifecycle(LoopState.Lifecycle.RUNNING);
        if (emitter != null) {
            emitter.emit(AgentEvent.LOOP_START, Map.of("budget",
                state.budget().getRemaining() + state.budget().getUsed()));
        }

        StringBuilder responseBuilder = new StringBuilder();

        while (state.budget().hasRemaining() && state.isRunning() && !state.isInterrupted()) {
            if (!state.budget().consume()) {
                responseBuilder.append("\n[Reached maximum iterations]");
                break;
            }

            // Governance check
            if (ctx.governancePolicy() != null && ctx.governancePolicy().isPaused()) {
                responseBuilder.append("\n⚠️ Agent paused: ")
                    .append(ctx.governancePolicy().getPauseReason());
                break;
            }

            try {
                // Hook: PRE_LLM_CALL
                if (emitter != null) {
                    emitter.emit(AgentEvent.PRE_LLM, Map.of("iteration", state.budget().getUsed()));
                }
                invokeHook(ctx, HookType.PRE_LLM_CALL, state);

                // Context budget enforcement
                enforceContextBudget(ctx, state, emitter);

                // LLM call
                var response = ctx.modelClient().chatCompletion(
                    state.history(),
                    buildToolDefinitions(ctx),
                    false,
                    ctx.modelParams()
                );

                // Hook: POST_LLM_CALL
                invokeHook(ctx, HookType.POST_LLM_CALL, state);
                if (emitter != null) {
                    emitter.emit(AgentEvent.POST_LLM, Map.of(
                        "finishReason", response.getFinishReason() != null ? response.getFinishReason() : "stop",
                        "hasToolCalls", response.hasToolCalls()
                    ));
                }

                ModelMessage assistantMessage = response.getMessage();
                if (assistantMessage == null) {
                    responseBuilder.append("\n[No response from model]");
                    break;
                }

                recordModelUsage(ctx, response);
                state.addToHistory(assistantMessage);

                if (response.hasToolCalls()) {
                    // Append assistant text
                    if (assistantMessage.getContent() != null && !assistantMessage.getContent().isEmpty()) {
                        if (responseBuilder.length() > 0) responseBuilder.append("\n\n");
                        responseBuilder.append(assistantMessage.getContent());
                    }

                    List<ToolCall> toolCalls = assistantMessage.getToolCalls();
                    List<LoopCheckpoint.ToolCallResult> completed = new ArrayList<>();

                    for (int i = 0; i < toolCalls.size(); i++) {
                        ToolCall tc = toolCalls.get(i);

                        if (emitter != null) {
                            emitter.emit(AgentEvent.PRE_TOOL, Map.of(
                                "callId", tc.getId(),
                                "tool", tc.getFunction().getName(),
                                "args", tc.getFunction().getArguments()
                            ));
                        }

                        // Approval check
                        if (needsApproval(ctx, tc)) {
                            state.snapshot(assistantMessage, toolCalls, i, completed,
                                state.budget().getRemaining(), state.userTurnCount());
                            state.setLifecycle(LoopState.Lifecycle.PAUSED_APPROVAL);

                            if (emitter != null) {
                                emitter.emit(AgentEvent.APPROVAL_NEEDED, Map.of(
                                    "callId", tc.getId(),
                                    "tool", tc.getFunction().getName(),
                                    "risk", assessRisk(tc)
                                ));
                            }

                            // Fire callback
                            if (ctx.toolApprovalCallback() != null) {
                                try {
                                    ctx.toolApprovalCallback().accept(
                                        new TenantAwareAIAgent.ToolApprovalRequiredException(
                                            tc.getFunction().getName(),
                                            tc.getFunction().getArguments(),
                                            ctx.agentId(),
                                            "tool-level approval rule",
                                            "Tool '" + tc.getFunction().getName() + "' requires approval"
                                        ));
                                } catch (Exception ignored) {}
                            }

                            return new LoopResult.Paused(state);
                        }

                        // Execute tool
                        long toolStart = System.currentTimeMillis();
                        String result = executeTool(ctx, tc);
                        long duration = System.currentTimeMillis() - toolStart;

                        if (emitter != null) {
                            emitter.emit(AgentEvent.POST_TOOL, Map.of(
                                "callId", tc.getId(),
                                "ok", !result.contains("\"error\""),
                                "durationMs", duration
                            ));
                        }

                        completed.add(new LoopCheckpoint.ToolCallResult(tc.getId(), result));
                        state.addToHistory(ModelMessage.tool(result, tc.getId()));
                    }

                    state.incrementItersSinceSkill();
                    // Continue loop
                } else {
                    // No tool calls = final response
                    String content = assistantMessage.getContent();
                    if (content != null && !content.isEmpty()) {
                        if (responseBuilder.length() > 0) responseBuilder.append("\n\n");
                        responseBuilder.append(content);
                    }
                    break;
                }

                if ("stop".equals(response.getFinishReason())) {
                    break;
                }

            } catch (TenantAwareAIAgent.ToolApprovalRequiredException ex) {
                throw ex;
            } catch (Exception e) {
                logger.error("Error in loop: {}", e.getMessage(), e);
                if (emitter != null) {
                    emitter.emit(AgentEvent.ERROR, Map.of("message", e.getMessage()));
                }
                responseBuilder.append("\n[Error: ").append(e.getMessage()).append("]");
                state.setLifecycle(LoopState.Lifecycle.FAILED);
                break;
            }
        }

        state.setLifecycle(LoopState.Lifecycle.IDLE);
        if (emitter != null) {
            emitter.emit(AgentEvent.LOOP_END, Map.of(
                "iterations", state.budget().getUsed(),
                "messages", state.historySize()
            ));
        }
        return new LoopResult.Completed(responseBuilder.toString());
    }

    // ===== Tool execution (delegates to TenantAwareToolDispatcher) =====

    @SuppressWarnings("unchecked")
    private static String executeTool(AgentContext ctx, ToolCall toolCall) {
        String toolName = toolCall.getFunction().getName();
        String arguments = toolCall.getFunction().getArguments();

        // Role permission check
        if (ctx.role() != null) {
            if (!ctx.role().getAllowedTools().isEmpty()
                    && !ctx.role().getAllowedTools().contains(toolName)) {
                return "{\"error\":\"Access denied: '" + toolName
                    + "' not allowed for role '" + ctx.role().getRoleName() + "'\"}";
            }
            if (ctx.role().getDeniedTools().contains(toolName)) {
                return "{\"error\":\"Access denied: '" + toolName
                    + "' is denied for role '" + ctx.role().getRoleName() + "'\"}";
            }
        }

        try {
            Map<String, Object> args = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(arguments, Map.class);

            if (ctx.toolDispatcher() != null) {
                return ctx.toolDispatcher().dispatch(toolName, args);
            }

            var entry = com.nousresearch.hermes.tools.ToolRegistry.getInstance()
                .getAllTools().stream()
                .filter(t -> t.getName().equals(toolName))
                .findFirst().orElse(null);
            if (entry != null) {
                return entry.getHandler().apply(args);
            }
            return "{\"error\":\"Unknown tool: " + toolName + "\"}";

        } catch (Exception e) {
            return "{\"error\":\"Execution failed: " + e.getMessage() + "\"}";
        }
    }

    // ===== Approval check (mirrors TenantAwareAIAgent logic) =====

    private static boolean needsApproval(AgentContext ctx, ToolCall tc) {
        String toolName = tc.getFunction().getName();
        if (ctx.role() == null || ctx.role().getToolApprovalRules().isEmpty()) return false;
        String argsStr = tc.getFunction().getArguments() != null
            ? tc.getFunction().getArguments().toLowerCase() : "";

        for (String rule : ctx.role().getToolApprovalRules()) {
            if (rule == null || rule.isBlank()) continue;
            String n = rule.trim().toLowerCase();
            if ("always".equals(n)) return true;
            if (("high-risk".equals(n) || "high-risk-tools".equals(n)) && isHighRisk(toolName)) return true;
            if (("external".equals(n) || "external-tools".equals(n)) && isExternal(toolName)) return true;
            if (n.startsWith("tool:") && toolName.toLowerCase().equals(n.substring(5).trim())) return true;
            if (n.startsWith("contains:") && argsStr.contains(n.substring(9).trim())) return true;
        }
        return false;
    }

    private static String assessRisk(ToolCall tc) {
        String name = tc.getFunction().getName().toLowerCase();
        if (name.contains("exec") || name.contains("delete") || name.contains("remove")) return "HIGH";
        if (name.contains("write") || name.contains("send")) return "MEDIUM";
        return "LOW";
    }

    private static boolean isHighRisk(String name) {
        String l = name.toLowerCase();
        return l.contains("exec") || l.contains("delete") || l.contains("remove")
            || l.contains("write") || l.contains("send_") || l.contains("post");
    }

    private static boolean isExternal(String name) {
        String l = name.toLowerCase();
        return l.contains("send") || l.contains("email") || l.contains("post")
            || l.contains("browser") || l.contains("web_fetch");
    }

    // ===== Helpers =====

    private static void invokeHook(AgentContext ctx, HookType type, LoopState state) {
        if (ctx.hookEngine() == null) return;
        var hookCtx = new HashMap<String, Object>();
        hookCtx.put("messages", new ArrayList<>(state.history()));
        hookCtx.put("session_id", ctx.sessionId());
        hookCtx.put("tenant_id", ctx.tenantId());
        hookCtx.put("turn", state.userTurnCount());
        ctx.hookEngine().invoke(type, hookCtx);
    }

    private static void enforceContextBudget(AgentContext ctx, LoopState state, EventEmitter emitter) {
        int totalChars = 0;
        for (ModelMessage m : state.history()) {
            if (m.getContent() != null) totalChars += m.getContent().length();
        }
        int limit = 400_000;
        if (totalChars <= limit) return;

        int target = (int) (limit * 0.75);
        int dropped = 0;
        int i = 1; // preserve system (index 0)
        while (totalChars > target && state.history().size() > 6 && i < state.history().size() - 4) {
            ModelMessage m = state.history().remove(i);
            totalChars -= m.getContent() == null ? 0 : m.getContent().length();
            dropped++;
        }
        if (dropped > 0 && emitter != null) {
            emitter.emit(AgentEvent.CONTEXT_COMPRESSED, Map.of("dropped", dropped));
        }
    }

    private static void recordModelUsage(AgentContext ctx, ChatCompletionResponse response) {
        if (response == null || response.getUsage() == null) return;
        var usage = response.getUsage();
        long total = usage.getTotalTokens() > 0 ? usage.getTotalTokens()
            : usage.getPromptTokens() + usage.getCompletionTokens();
        if (ctx.quotaManager() != null) {
            try {
                ctx.quotaManager().getStoreIfAvailable()
                    .ifPresent(store -> store.addAndGetDailyTokens(total));
            } catch (Exception ignored) {}
        }
    }

    @SuppressWarnings("unchecked")
    private static List<com.nousresearch.hermes.model.ToolDefinition> buildToolDefinitions(AgentContext ctx) {
        var registry = com.nousresearch.hermes.tools.ToolRegistry.getInstance();
        Set<String> toolNames = new HashSet<>(registry.getAllToolNames());

        if (ctx.tenantContext() != null) {
            var allowed = ctx.tenantContext().getSecurityPolicy().getAllowedTools();
            var denied = ctx.tenantContext().getSecurityPolicy().getDeniedTools();
            if (!allowed.isEmpty()) toolNames.retainAll(allowed);
            toolNames.removeAll(denied);
        }

        if (ctx.role() != null) {
            if (!ctx.role().getAllowedTools().isEmpty())
                toolNames.retainAll(ctx.role().getAllowedTools());
            if (!ctx.role().getDeniedTools().isEmpty())
                toolNames.removeAll(ctx.role().getDeniedTools());
        }

        List<Map<String, Object>> defs = registry.getDefinitions(toolNames, false);
        List<com.nousresearch.hermes.model.ToolDefinition> result = new ArrayList<>();
        for (Map<String, Object> def : defs) {
            Map<String, Object> function = (Map<String, Object>) def.get("function");
            if (function != null) {
                result.add(com.nousresearch.hermes.model.ToolDefinition.builder()
                    .name((String) function.get("name"))
                    .description((String) function.get("description"))
                    .parameters((Map<String, Object>) function.get("parameters"))
                    .build());
            }
        }
        return result;
    }

    // ===== executeOnAgent: operates directly on TenantAwareAIAgent fields =====

    /**
     * Execute loop directly on the agent's own conversationHistory and iterationBudget.
     * This avoids synchronization between two history lists.
     */
    public static String executeOnAgent(TenantAwareAIAgent agent, EventEmitter emitter) {
        var history = agent.getConversationHistory();
        var budget = agent.getIterationBudget();

        StringBuilder responseBuilder = new StringBuilder();
        if (emitter != null) {
            emitter.emit(AgentEvent.LOOP_START, Map.of("budget", budget.getRemaining() + budget.getUsed()));
        }

        while (budget.hasRemaining() && !agent.isInterrupted()) {
            if (!budget.consume()) {
                responseBuilder.append("\n[Reached maximum iterations]");
                break;
            }

            if (agent.getGovernancePolicy() != null && agent.getGovernancePolicy().isPaused()) {
                responseBuilder.append("\n⚠️ Agent paused: ").append(agent.getGovernancePolicy().getPauseReason());
                break;
            }

            try {
                if (emitter != null) emitter.emit(AgentEvent.PRE_LLM, Map.of("iteration", budget.getUsed()));

                // Hook: PRE_LLM_CALL
                if (agent.getHookEngine() != null) {
                    Map<String, Object> preCtx = new HashMap<>();
                    preCtx.put("messages", new ArrayList<>(history));
                    preCtx.put("session_id", agent.getSessionId());
                    preCtx.put("tenant_id", agent.getTenantId());
                    agent.getHookEngine().invoke(HookType.PRE_LLM_CALL, preCtx);
                }

                enforceContextBudgetOnList(history, emitter);

                var response = agent.getModelClient().chatCompletion(
                    history, agent.buildToolDefinitionsForHarness(), false, agent.getModelParams());

                if (agent.getHookEngine() != null) {
                    Map<String, Object> postCtx = new HashMap<>();
                    postCtx.put("message", response.getMessage());
                    postCtx.put("finish_reason", response.getFinishReason());
                    postCtx.put("session_id", agent.getSessionId());
                    postCtx.put("tenant_id", agent.getTenantId());
                    agent.getHookEngine().invoke(HookType.POST_LLM_CALL, postCtx);
                }

                if (emitter != null) {
                    emitter.emit(AgentEvent.POST_LLM, Map.of(
                        "finishReason", response.getFinishReason() != null ? response.getFinishReason() : "stop",
                        "hasToolCalls", response.hasToolCalls()));
                }

                ModelMessage assistantMessage = response.getMessage();
                if (assistantMessage == null) {
                    responseBuilder.append("\n[No response from model]");
                    break;
                }

                agent.recordModelUsageForHarness(response);
                history.add(assistantMessage);
                agent.autoSaveSessionForHarness();

                if (response.hasToolCalls()) {
                    if (assistantMessage.getContent() != null && !assistantMessage.getContent().isEmpty()) {
                        if (responseBuilder.length() > 0) responseBuilder.append("\n\n");
                        responseBuilder.append(assistantMessage.getContent());
                    }

                    for (ToolCall tc : assistantMessage.getToolCalls()) {
                        if (emitter != null) {
                            emitter.emit(AgentEvent.PRE_TOOL, Map.of(
                                "callId", tc.getId(),
                                "tool", tc.getFunction().getName(),
                                "args", tc.getFunction().getArguments()));
                        }

                        long toolStart = System.currentTimeMillis();
                        String result = agent.executeToolCall(tc);  // throws ToolApprovalRequiredException
                        long duration = System.currentTimeMillis() - toolStart;

                        if (emitter != null) {
                            emitter.emit(AgentEvent.POST_TOOL, Map.of(
                                "callId", tc.getId(),
                                "ok", !result.contains("\"error\""),
                                "durationMs", duration));
                        }

                        history.add(ModelMessage.tool(result, tc.getId()));
                    }
                    agent.incrementItersSinceSkillForHarness();
                } else {
                    String content = assistantMessage.getContent();
                    if (content != null && !content.isEmpty()) {
                        if (responseBuilder.length() > 0) responseBuilder.append("\n\n");
                        responseBuilder.append(content);
                    }
                    break;
                }

                if ("stop".equals(response.getFinishReason())) break;

            } catch (TenantAwareAIAgent.ToolApprovalRequiredException ex) {
                throw ex;
            } catch (Exception e) {
                logger.error("Error in loop: {}", e.getMessage(), e);
                if (emitter != null) emitter.emit(AgentEvent.ERROR, Map.of("message", e.getMessage()));
                responseBuilder.append("\n[Error: ").append(e.getMessage()).append("]");
                break;
            }
        }

        if (emitter != null) {
            emitter.emit(AgentEvent.LOOP_END, Map.of("iterations", budget.getUsed(), "messages", history.size()));
        }
        return responseBuilder.toString();
    }

    private static void enforceContextBudgetOnList(List<ModelMessage> history, EventEmitter emitter) {
        int totalChars = 0;
        for (ModelMessage m : history) {
            if (m.getContent() != null) totalChars += m.getContent().length();
        }
        int limit = 400_000;
        if (totalChars <= limit) return;

        int target = (int) (limit * 0.75);
        int dropped = 0;
        int i = 1;
        while (totalChars > target && history.size() > 6 && i < history.size() - 4) {
            ModelMessage m = history.remove(i);
            totalChars -= m.getContent() == null ? 0 : m.getContent().length();
            dropped++;
        }
        if (dropped > 0 && emitter != null) {
            emitter.emit(AgentEvent.CONTEXT_COMPRESSED, Map.of("dropped", dropped));
        }
    }
}
