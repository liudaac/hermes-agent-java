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
 * Stateless agent loop: think → act → observe cycle.
 *
 * <p>Extracted from {@code TenantAwareAIAgent.doProcessMessage()}.
 * All state lives in {@link LoopState}; all dependencies are in
 * {@link AgentContext}. AgentLoop itself holds zero state.</p>
 *
 * <p>Two entry points:
 * <ul>
 *   <li>{@link #run} - start a new turn with a user message</li>
 *   <li>{@link #resume} - continue after an approval decision</li>
 * </ul></p>
 */
public class AgentLoop {
    private static final Logger logger = LoggerFactory.getLogger(AgentLoop.class);

    /** Soft context window limit (tokens, ~4 chars/token). */
    private static final int DEFAULT_CONTEXT_CHARS = 400_000;

    private final AgentContext ctx;
    private final LoopState state;
    private final EventEmitter emitter;

    public AgentLoop(AgentContext ctx, LoopState state, EventEmitter emitter) {
        this.ctx = ctx;
        this.state = state;
        this.emitter = emitter;
    }

    // ==================== Run ====================

    /**
     * Execute one user turn: add user message, run the loop until completion,
     * pause, or failure.
     */
    public LoopResult run(String userMessage) {
        state.setLifecycle(LoopState.Lifecycle.RUNNING);
        emitter.emit(AgentEvent.LOOP_START, Map.of("budget", state.budget().getRemaining() + state.budget().getUsed()));

        // Hook: ON_SESSION_START (first user message)
        if (state.userTurnCount() == 0 && ctx.hookEngine() != null) {
            var sessionCtx = new HashMap<String, Object>();
            sessionCtx.put("session_id", ctx.sessionId());
            sessionCtx.put("tenant_id", ctx.tenantId());
            sessionCtx.put("message", userMessage);
            ctx.hookEngine().invoke(HookType.ON_SESSION_START, sessionCtx);
        }

        // Ensure system prompt at start
        if (state.history().isEmpty()) {
            state.addToHistory(ModelMessage.system(buildSystemPrompt()));
        }

        state.incrementTurn();
        state.addToHistory(ModelMessage.user(userMessage));

        // Memory card (smart context injection)
        if (ctx.smartMemoryCardEnabled() && ctx.memoryCardIntegrator() != null) {
            ctx.memoryCardIntegrator().beforeTurn(state.history(), userMessage);
        }

        return executeLoop(null);
    }

    // ==================== Resume ====================

    /**
     * Resume after an approval decision.
     */
    public LoopResult resume(String toolCallId, boolean approved, String reason) {
        LoopCheckpoint cp = state.checkpoint();
        if (cp == null) {
            return new LoopResult.Failed("No checkpoint to resume from");
        }

        ToolCall pending = cp.pendingToolCall();
        if (!pending.getId().equals(toolCallId)) {
            return new LoopResult.Failed("Tool call ID mismatch");
        }

        state.clearCheckpoint();
        state.setLifecycle(LoopState.Lifecycle.RUNNING);

        // Execute or reject the pending tool
        String result;
        if (approved) {
            result = executeTool(pending);
        } else {
            result = "{\"error\":\"Rejected: " + (reason != null ? reason : "no reason") + "\"}";
        }
        state.addToHistory(ModelMessage.tool(result, pending.getId()));

        // Execute remaining tool calls after the pending one
        for (int i = cp.pendingIndex() + 1; i < cp.toolCalls().size(); i++) {
            ToolCall tc = cp.toolCalls().get(i);
            result = executeTool(tc);
            state.addToHistory(ModelMessage.tool(result, tc.getId()));
        }

        // Continue the main loop
        return executeLoop(null);
    }

    // ==================== Core loop ====================

    private LoopResult executeLoop(String contentPrefix) {
        StringBuilder responseBuilder = new StringBuilder();
        if (contentPrefix != null && !contentPrefix.isEmpty()) {
            responseBuilder.append(contentPrefix);
        }

        while (state.budget().hasRemaining() && state.isRunning() && !state.isInterrupted()) {
            if (!state.budget().consume()) {
                responseBuilder.append("\n[Reached maximum iterations]");
                break;
            }

            // Governance check
            if (ctx.governancePolicy() != null && ctx.governancePolicy().isPaused()) {
                state.setLifecycle(LoopState.Lifecycle.PAUSED_GOVERNANCE);
                responseBuilder.append("\n⚠️ Agent paused: ")
                    .append(ctx.governancePolicy().getPauseReason());
                break;
            }

            try {
                // Hook: PRE_LLM_CALL
                emitter.emit(AgentEvent.PRE_LLM, Map.of("iteration", state.budget().getUsed()));
                invokeHook(HookType.PRE_LLM_CALL);

                enforceContextBudget();

                var response = ctx.modelClient().chatCompletion(
                    state.history(),
                    buildToolDefinitions(),
                    false,
                    ctx.modelParams()
                );

                // Hook: POST_LLM_CALL
                invokeHook(HookType.POST_LLM_CALL);
                emitter.emit(AgentEvent.POST_LLM, Map.of(
                    "finishReason", response.getFinishReason() != null ? response.getFinishReason() : "stop",
                    "hasToolCalls", response.hasToolCalls()
                ));

                ModelMessage assistantMessage = response.getMessage();
                if (assistantMessage == null) {
                    responseBuilder.append("\n[No response from model]");
                    break;
                }

                recordModelUsage(response);
                state.addToHistory(assistantMessage);

                if (response.hasToolCalls()) {
                    // Append assistant text content
                    if (assistantMessage.getContent() != null && !assistantMessage.getContent().isEmpty()) {
                        if (responseBuilder.length() > 0) responseBuilder.append("\n\n");
                        responseBuilder.append(assistantMessage.getContent());
                    }

                    // Process tool calls
                    List<ToolCall> toolCalls = assistantMessage.getToolCalls();
                    List<LoopCheckpoint.ToolCallResult> completed = new ArrayList<>();

                    for (int i = 0; i < toolCalls.size(); i++) {
                        ToolCall tc = toolCalls.get(i);

                        emitter.emit(AgentEvent.PRE_TOOL, Map.of(
                            "callId", tc.getId(),
                            "tool", tc.getFunction().getName(),
                            "args", tc.getFunction().getArguments()
                        ));

                        // Approval check
                        if (needsApproval(tc)) {
                            state.snapshot(assistantMessage, toolCalls, i, completed,
                                state.budget().getRemaining(), state.userTurnCount());
                            state.setLifecycle(LoopState.Lifecycle.PAUSED_APPROVAL);

                            emitter.emit(AgentEvent.APPROVAL_NEEDED, Map.of(
                                "callId", tc.getId(),
                                "tool", tc.getFunction().getName(),
                                "risk", assessRisk(tc)
                            ));

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
                        String result = executeTool(tc);
                        long duration = System.currentTimeMillis() - toolStart;

                        emitter.emit(AgentEvent.POST_TOOL, Map.of(
                            "callId", tc.getId(),
                            "ok", !result.contains("\"error\""),
                            "durationMs", duration
                        ));

                        completed.add(new LoopCheckpoint.ToolCallResult(tc.getId(), result));
                        state.addToHistory(ModelMessage.tool(result, tc.getId()));
                    }

                    state.incrementItersSinceSkill();
                    // Continue loop (LLM sees tool results)
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
                emitter.emit(AgentEvent.ERROR, Map.of("message", e.getMessage()));
                state.setLifecycle(LoopState.Lifecycle.FAILED);
                responseBuilder.append("\n[Error: ").append(e.getMessage()).append("]");
                break;
            }
        }

        state.setLifecycle(LoopState.Lifecycle.IDLE);
        emitter.emit(AgentEvent.LOOP_END, Map.of(
            "iterations", state.budget().getUsed(),
            "messages", state.historySize()
        ));

        return new LoopResult.Completed(responseBuilder.toString());
    }

    // ==================== Tool execution ====================

    private String executeTool(ToolCall toolCall) {
        String toolName = toolCall.getFunction().getName();
        String arguments = toolCall.getFunction().getArguments();
        logger.debug("Executing tool: {} for tenant: {}", toolName, ctx.tenantId());

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
            @SuppressWarnings("unchecked")
            Map<String, Object> args = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(arguments, Map.class);

            if (ctx.toolDispatcher() != null) {
                return ctx.toolDispatcher().dispatch(toolName, args);
            }

            // Fallback to global registry
            var entry = com.nousresearch.hermes.tools.ToolRegistry.getInstance()
                .getAllTools().stream()
                .filter(t -> t.getName().equals(toolName))
                .findFirst().orElse(null);
            if (entry != null) {
                return entry.getHandler().apply(args);
            }
            return "{\"error\":\"Unknown tool: " + toolName + "\"}";

        } catch (Exception e) {
            logger.error("Tool execution failed: {}", toolName, e);
            return "{\"error\":\"Execution failed: " + e.getMessage() + "\"}";
        }
    }

    // ==================== Approval check ====================

    private boolean needsApproval(ToolCall tc) {
        String toolName = tc.getFunction().getName();
        if (ctx.role() == null || ctx.role().getToolApprovalRules().isEmpty()) {
            return false;
        }
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

    private String assessRisk(ToolCall tc) {
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

    // ==================== Helpers ====================

    private void invokeHook(HookType type) {
        if (ctx.hookEngine() == null) return;
        var hookCtx = new HashMap<String, Object>();
        hookCtx.put("messages", new ArrayList<>(state.history()));
        hookCtx.put("session_id", ctx.sessionId());
        hookCtx.put("tenant_id", ctx.tenantId());
        hookCtx.put("turn", state.userTurnCount());
        ctx.hookEngine().invoke(type, hookCtx);
    }

    private void enforceContextBudget() {
        int totalChars = 0;
        for (ModelMessage m : state.history()) {
            if (m.getContent() != null) totalChars += m.getContent().length();
        }
        if (totalChars <= DEFAULT_CONTEXT_CHARS) return;

        int target = (int) (DEFAULT_CONTEXT_CHARS * 0.75);
        int dropped = 0;
        int i = 1; // preserve system (index 0)
        while (totalChars > target && state.history().size() > 6 && i < state.history().size() - 4) {
            ModelMessage m = state.history().remove(i);
            totalChars -= m.getContent() == null ? 0 : m.getContent().length();
            dropped++;
        }
        if (dropped > 0) {
            emitter.emit(AgentEvent.CONTEXT_COMPRESSED, Map.of("dropped", dropped));
            logger.info("Enforced context budget, dropped {} messages", dropped);
        }
    }

    private void recordModelUsage(ChatCompletionResponse response) {
        if (response == null || response.getUsage() == null) return;
        var usage = response.getUsage();
        long total = usage.getTotalTokens() > 0 ? usage.getTotalTokens()
            : usage.getPromptTokens() + usage.getCompletionTokens();

        // Update quota
        if (ctx.quotaManager() != null) {
            try {
                ctx.quotaManager().getStoreIfAvailable()
                    .ifPresent(store -> store.addAndGetDailyTokens(total));
            } catch (Exception e) {
                logger.debug("Failed to update quota: {}", e.getMessage());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private List<com.nousresearch.hermes.model.ToolDefinition> buildToolDefinitions() {
        var registry = com.nousresearch.hermes.tools.ToolRegistry.getInstance();
        Set<String> toolNames = new HashSet<>(registry.getAllToolNames());

        // Tenant security policy filter
        if (ctx.tenantContext() != null) {
            var allowed = ctx.tenantContext().getSecurityPolicy().getAllowedTools();
            var denied = ctx.tenantContext().getSecurityPolicy().getDeniedTools();
            if (!allowed.isEmpty()) toolNames.retainAll(allowed);
            toolNames.removeAll(denied);
        }

        // Role filter
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

    private String buildSystemPrompt() {
        String custom = ctx.customSystemPrompt();
        if (custom != null && !custom.isBlank()) return custom;

        StringBuilder prompt = new StringBuilder();
        prompt.append(Constants.DEFAULT_AGENT_IDENTITY).append("\n\n");
        prompt.append(Constants.MEMORY_GUIDANCE).append("\n\n");
        prompt.append(Constants.TOOL_USE_ENFORCEMENT_GUIDANCE).append("\n\n");
        prompt.append(Constants.EXECUTION_DISCIPLINE_GUIDANCE).append("\n\n");
        prompt.append(Constants.SESSION_SEARCH_GUIDANCE).append("\n\n");
        prompt.append(Constants.SKILLS_GUIDANCE).append("\n\n");

        if (ctx.memoryStore() != null) {
            String memCtx = ctx.memoryStore().getSystemPromptSnapshot();
            if (!memCtx.isEmpty()) prompt.append(memCtx).append("\n\n");
        }

        if (ctx.toolPerformanceTracker() != null) {
            String hints = ctx.toolPerformanceTracker().buildHintBlock();
            if (!hints.isEmpty()) prompt.append(hints).append("\n");
        }

        if (ctx.evolutionEngine() != null) {
            String evoCtx = ctx.evolutionEngine().buildEvolutionPrompt(ctx.agentId());
            if (!evoCtx.isBlank() && !evoCtx.trim().equals("# Self-Evolution Context")) {
                prompt.append(evoCtx).append("\n");
            }
        }

        if (ctx.team() != null) {
            prompt.append(ctx.team().describeForPrompt()).append("\n");
        }

        return prompt.toString();
    }
}
