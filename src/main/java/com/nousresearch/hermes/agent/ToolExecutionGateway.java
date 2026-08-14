package com.nousresearch.hermes.agent;

import com.nousresearch.hermes.config.HermesConfig;
import com.nousresearch.hermes.model.ModelMessage;
import com.nousresearch.hermes.model.ToolCall;
import com.nousresearch.hermes.model.ToolDefinition;
import com.nousresearch.hermes.monitoring.AgentEvalMetrics;
import com.nousresearch.hermes.org.evolution.FailureCase;
import com.nousresearch.hermes.org.evolution.SelfEvolutionEngine;
import com.nousresearch.hermes.org.observe.AgentTrace;
import com.nousresearch.hermes.policy.PolicyService;
import com.nousresearch.hermes.tools.TenantAwareToolDispatcher;
import com.nousresearch.hermes.tools.ToolPerformanceTracker;
import com.nousresearch.hermes.tools.ToolRegistry;
import com.nousresearch.hermes.approval.ApprovalMessageHandler;
import com.nousresearch.hermes.approval.ApprovalSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Extracted from TenantAwareAIAgent: handles tool execution, approval flow,
 * tool definitions, and related checkpoint/resume logic.
 */
public class ToolExecutionGateway {
    private static final Logger logger = LoggerFactory.getLogger(ToolExecutionGateway.class);

    private final TenantAwareAIAgent agent;

    // ===== Approval checkpoint state (lives here because it's tool-specific) =====
    private volatile boolean approvalCheckpointActive = false;
    private ToolApprovalCheckpoint approvalCheckpoint;
    private java.util.function.Consumer<TenantAwareAIAgent.ToolApprovalRequiredException> toolApprovalCallback;

    public ToolExecutionGateway(TenantAwareAIAgent agent) {
        this.agent = agent;
    }

    // ==================== Tool Execution ====================

    /**
     * Execute a tool by name with args map. Used by CodeModeTool.
     */
    public String executeToolByName(String toolName, java.util.Map<String, Object> args) {
        try {
            TenantAwareToolDispatcher dispatcher = agent.getToolDispatcher();
            if (dispatcher != null) {
                return dispatcher.dispatch(toolName, args);
            }
            var entry = ToolRegistry.getInstance().getEntry(toolName);
            if (entry != null) {
                return entry.getHandler().apply(args);
            }
            return ToolRegistry.toolError("Unknown tool: " + toolName);
        } catch (Exception e) {
            return ToolRegistry.toolError("Tool execution failed: " + e.getMessage());
        }
    }

    public String executeToolCall(ToolCall toolCall) {
        String toolName = toolCall.getFunction().getName();
        String arguments = toolCall.getFunction().getArguments();
        String tenantId = agent.getTenantId();
        String agentId = agent.getAgentId();
        AgentTrace currentTrace = agent.getCurrentTrace();
        SelfEvolutionEngine evolutionEngine = agent.getEvolutionEngine();

        logger.debug("Executing tool: {} for tenant: {}", toolName, tenantId);

        // ======== AI原生组织：第五刀--可观测性记录 ========
        long toolStartMs = System.currentTimeMillis();
        if (currentTrace != null) {
            currentTrace.step(AgentTrace.Step.toolCall(
                toolName, arguments, java.util.List.of(), 1.0, 0, 0.0));
        }

        // ======== AI原生组织：角色权限检查 ========
        var agentRole = agent.getAgentRole();
        if (agentRole != null && !agentRole.getAllowedTools().isEmpty()
                && !agentRole.getAllowedTools().contains(toolName)) {
            String msg = "Access denied: '" + toolName + "' not allowed for role '" + agentRole.getRoleName() + "'";
            logger.warn("Tenant {} agent {} {}", tenantId, agentId, msg);
            if (currentTrace != null) {
                currentTrace.step(AgentTrace.Step.error(msg));
            }
            return ToolRegistry.toolError(msg);
        }
        if (agentRole != null && agentRole.getDeniedTools().contains(toolName)) {
            String msg = "Access denied: '" + toolName + "' is denied for role '" + agentRole.getRoleName() + "'";
            logger.warn("Tenant {} agent {} {}", tenantId, agentId, msg);
            if (currentTrace != null) {
                currentTrace.step(AgentTrace.Step.error(msg));
            }
            return ToolRegistry.toolError(msg);
        }

        // ======== 工具级审批检查 ========
        if (agentRole != null && !agentRole.getToolApprovalRules().isEmpty()) {
            var approvalCheck = checkToolApproval(toolName, arguments);
            if (approvalCheck.approvalNeeded()) {
                String msg = "Tool approval required: '" + toolName + "' - " + approvalCheck.reason();
                logger.info("Tenant {} agent {} {}", tenantId, agentId, msg);
                if (currentTrace != null) {
                    currentTrace.step(AgentTrace.Step.error(msg));
                }
                throw new TenantAwareAIAgent.ToolApprovalRequiredException(
                    toolName, arguments, approvalCheck.agentId(), approvalCheck.matchedRule(), approvalCheck.reason());
            }
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> args = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(arguments, Map.class);

            String result;

            // Use persistent TenantAwareToolDispatcher (approval-aware)
            TenantAwareToolDispatcher dispatcher = agent.getToolDispatcher();
            if (dispatcher != null) {
                result = dispatcher.dispatch(toolName, args);
            } else {
                // Fallback to global registry (non-tenant mode)
                var entry = ToolRegistry.getInstance().getAllTools().stream()
                    .filter(t -> t.getName().equals(toolName))
                    .findFirst()
                    .orElse(null);
                if (entry != null) {
                    result = entry.getHandler().apply(args);
                } else {
                    result = ToolRegistry.toolError("Unknown tool: " + toolName);
                }
            }

            // AI原生组织：第五刀--记录工具结果到追踪
            if (currentTrace != null) {
                long duration = System.currentTimeMillis() - toolStartMs;
                currentTrace.step(AgentTrace.Step.toolResult(
                    toolName, result, duration));
            }

            // AI原生组织：记录成功模式，强化有效策略
            if (evolutionEngine != null && !result.contains("\"error\"")) {
                evolutionEngine.recordSuccess(agentId, toolName,
                    "Tool '" + toolName + "' executed with args: " + args.keySet());
            }

            return result;

        } catch (Exception e) {
            logger.error("Tool execution failed: {}", toolName, e);

            // AI原生组织：第五刀--记录错误到追踪
            if (currentTrace != null) {
                currentTrace.step(AgentTrace.Step.error(
                    toolName + ": " + e.getMessage()));
            }

            // AI原生组织：记录失败，驱动自我进化
            if (evolutionEngine != null) {
                var failure = new FailureCase.Builder(
                        agentId,
                        "Execute tool: " + toolName,
                        e.getMessage()
                    )
                    .rootCause(determineRootCause(e, toolName))
                    .severity(FailureCase.Severity.MEDIUM)
                    .lesson("Tool '" + toolName + "' failed: " + e.getClass().getSimpleName())
                    .build();
                evolutionEngine.recordFailure(failure);
            }

            return ToolRegistry.toolError("Execution failed: " + e.getMessage());
        }
    }

    // ======== AI原生组织：失败根因分析 ========
    static FailureCase.RootCause determineRootCause(Exception e, String toolName) {
        String msg = (e.getMessage() != null ? e.getMessage().toLowerCase() : "");
        if (msg.contains("permission") || msg.contains("denied") || msg.contains("access")) {
            return FailureCase.RootCause.PERMISSION_DENIED;
        }
        if (msg.contains("not found") || msg.contains("unknown") || msg.contains("no such")) {
            return FailureCase.RootCause.WRONG_TOOL;
        }
        if (msg.contains("timeout") || msg.contains("timed out")) {
            return FailureCase.RootCause.INSUFFICIENT_CONTEXT;
        }
        if (msg.contains("ambiguous") || msg.contains("unclear")) {
            return FailureCase.RootCause.AMBIGUOUS_PROMPT;
        }
        return FailureCase.RootCause.WRONG_TOOL;
    }

    // ==================== Tool Recording ====================

    public void recordToolCall(ToolCall toolCall, boolean ok, long durationMs) {
        try {
            var session = new com.nousresearch.hermes.gateway.SessionManager(
                com.nousresearch.hermes.config.Constants.getHermesHome())
                .getSession(agent.getSessionId());
            session.recordToolCall(toolCall.getFunction().getName(), ok, durationMs);
            ToolPerformanceTracker tpt = agent.getToolPerformanceTracker();
            if (tpt != null) {
                tpt.record(toolCall.getFunction().getName(), ok, durationMs);
            }
            AgentEvalMetrics evalMetrics = agent.getEvalMetrics();
            if (evalMetrics != null) {
                evalMetrics.recordToolCall(ok, durationMs);
            }
        } catch (Exception e) {
            logger.debug("Failed to record tool call: {}", e.getMessage());
        }
    }

    /**
     * Count how many tool-result messages appear after the last user message.
     */
    public int countToolsUsedThisTurn() {
        int count = 0;
        List<ModelMessage> history = agent.getConversationHistory();
        for (int i = history.size() - 1; i >= 0; i--) {
            ModelMessage m = history.get(i);
            if ("user".equals(m.getRole())) break;
            if ("tool".equals(m.getRole())) count++;
        }
        return count;
    }

    // ==================== Approval ====================

    /**
     * Initialize per-tenant approval system and wire into the tool dispatcher.
     */
    void initTenantApproval() {
        ApprovalSystem approvalSystem = new ApprovalSystem();
        ApprovalMessageHandler approvalMessageHandler = new ApprovalMessageHandler();
        agent.setApprovalSystem(approvalSystem);
        agent.setApprovalMessageHandler(approvalMessageHandler);

        // Wire to the persistent tool dispatcher
        TenantAwareToolDispatcher dispatcher = agent.getToolDispatcher();
        if (dispatcher != null) {
            dispatcher.setApprovalSystem(approvalSystem);
            dispatcher.setApprovalMessageHandler(approvalMessageHandler);
        }

        logger.info("Tenant approval system initialized for: {}", agent.getTenantId());
    }

    /**
     * Check if a tool call requires approval based on the agent role's toolApprovalRules.
     * Mirrors the rule semantics from PolicyService.checkToolApprovalRequired.
     */
    PolicyService.ApprovalCheckResult checkToolApproval(String toolName, String arguments) {
        var agentRole = agent.getAgentRole();
        if (agentRole == null || agentRole.getToolApprovalRules().isEmpty()) {
            return PolicyService.ApprovalCheckResult.noApprovalNeeded();
        }

        String argsStr = arguments != null ? arguments.toLowerCase() : "";
        String agentId = agent.getAgentId();

        for (String rule : agentRole.getToolApprovalRules()) {
            if (rule == null || rule.isBlank()) continue;
            String normalized = rule.trim().toLowerCase();

            if ("always".equals(normalized)) {
                return PolicyService.ApprovalCheckResult.approvalNeeded(
                    agentId, rule, "Every tool call requires approval");
            }

            if ("high-risk".equals(normalized) || "high-risk-tools".equals(normalized)) {
                if (isHighRiskTool(toolName)) {
                    return PolicyService.ApprovalCheckResult.approvalNeeded(
                        agentId, rule, "High-risk tool: " + toolName);
                }
            }

            if ("external".equals(normalized) || "external-tools".equals(normalized)) {
                if (isExternalTool(toolName)) {
                    return PolicyService.ApprovalCheckResult.approvalNeeded(
                        agentId, rule, "External tool: " + toolName);
                }
            }

            if (normalized.startsWith("tool:")) {
                String targetTool = normalized.substring("tool:".length()).trim();
                if (toolName.toLowerCase().equals(targetTool)) {
                    return PolicyService.ApprovalCheckResult.approvalNeeded(
                        agentId, rule, "Tool requires approval: " + toolName);
                }
            }

            if (normalized.startsWith("contains:")) {
                String keyword = normalized.substring("contains:".length()).trim();
                if (argsStr.contains(keyword)) {
                    return PolicyService.ApprovalCheckResult.approvalNeeded(
                        agentId, rule, "Keyword '" + keyword + "' detected in tool arguments");
                }
            }
        }
        return PolicyService.ApprovalCheckResult.noApprovalNeeded();
    }

    static boolean isHighRiskTool(String toolName) {
        String lower = toolName.toLowerCase();
        return lower.contains("exec") || lower.contains("delete") || lower.contains("remove")
            || lower.contains("write") || lower.contains("send_") || lower.contains("post")
            || lower.contains("email") || lower.contains("payment") || lower.contains("refund")
            || lower.contains("transfer") || lower.contains("publish");
    }

    static boolean isExternalTool(String toolName) {
        String lower = toolName.toLowerCase();
        return lower.contains("send") || lower.contains("email") || lower.contains("post")
            || lower.contains("tweet") || lower.contains("message") || lower.contains("browser")
            || lower.contains("web_fetch") || lower.contains("http");
    }

    // ==================== Tool Definitions ====================

    public List<ToolDefinition> buildToolDefinitions() {
        var registry = ToolRegistry.getInstance();
        Set<String> toolNames = new HashSet<>(registry.getAllToolNames());
        var tenantContext = agent.getTenantContext();

        // 如果处于租户模式，过滤掉不允许的工具（租户级）
        if (tenantContext != null) {
            var allowed = tenantContext.getSecurityPolicy().getAllowedTools();
            var denied = tenantContext.getSecurityPolicy().getDeniedTools();

            if (!allowed.isEmpty()) {
                toolNames.retainAll(allowed);
            }
            toolNames.removeAll(denied);
        }

        // Agent 角色级工具权限过滤（蓝图 / 业务策略生效点）
        var agentRole = agent.getAgentRole();
        if (agentRole != null) {
            if (!agentRole.getAllowedTools().isEmpty()) {
                toolNames.retainAll(agentRole.getAllowedTools());
            }
            if (!agentRole.getDeniedTools().isEmpty()) {
                toolNames.removeAll(agentRole.getDeniedTools());
            }
        }

        // Convert Map definitions to ToolDefinition objects
        List<Map<String, Object>> defs = registry.getDefinitions(toolNames, false);
        List<ToolDefinition> result = new ArrayList<>();
        for (Map<String, Object> def : defs) {
            Map<String, Object> function = (Map<String, Object>) def.get("function");
            if (function != null) {
                String name = (String) function.get("name");
                String description = (String) function.get("description");
                Map<String, Object> parameters = (Map<String, Object>) function.get("parameters");
                result.add(ToolDefinition.builder()
                    .name(name)
                    .description(description)
                    .parameters(parameters)
                    .build());
            }
        }
        return result;
    }

    // ==================== Approval Checkpoint & Resume ====================

    /** Set a callback that fires whenever a tool call requires approval. */
    public void setToolApprovalCallback(
            java.util.function.Consumer<TenantAwareAIAgent.ToolApprovalRequiredException> callback) {
        this.toolApprovalCallback = callback;
    }

    /** Check if this agent is currently paused waiting for tool approval. */
    public boolean isAwaitingToolApproval() {
        return approvalCheckpointActive && approvalCheckpoint != null;
    }

    /** Get info about the pending tool approval (if any). */
    public TenantAwareAIAgent.ToolApprovalRequiredException getPendingToolApproval() {
        if (!approvalCheckpointActive || approvalCheckpoint == null) return null;
        ToolCall pending = approvalCheckpoint.toolCalls.get(approvalCheckpoint.pendingIndex);
        return new TenantAwareAIAgent.ToolApprovalRequiredException(
            pending.getFunction().getName(),
            pending.getFunction().getArguments(),
            agent.getAgentId(),
            "tool-level approval rule",
            "Tool '" + pending.getFunction().getName() + "' requires approval"
        );
    }

    /**
     * Resume execution after a tool approval decision has been made.
     */
    public String resumeToolApproval(String toolCallId, boolean approved, String reason) {
        if (!approvalCheckpointActive || approvalCheckpoint == null) {
            throw new IllegalStateException("No pending tool approval");
        }

        ToolApprovalCheckpoint cp = approvalCheckpoint;
        ToolCall pendingTool = cp.toolCalls.get(cp.pendingIndex);

        // Verify the tool call ID matches
        if (!pendingTool.getId().equals(toolCallId)) {
            throw new IllegalArgumentException("Tool call ID mismatch: expected "
                + pendingTool.getId() + " but got " + toolCallId);
        }

        // Clear the checkpoint (we'll either succeed or fail completely)
        approvalCheckpointActive = false;
        approvalCheckpoint = null;

        StringBuilder responseBuilder = new StringBuilder();

        // Execute the pending tool (or inject rejection)
        String pendingResult;
        boolean toolOk;
        long toolStart = System.currentTimeMillis();
        try {
            if (approved) {
                pendingResult = executeToolCall(pendingTool);
                toolOk = true;
            } else {
                pendingResult = ToolRegistry.toolError(
                    "Tool call rejected by approver: " + (reason != null ? reason : "no reason provided"));
                toolOk = false;
            }
        } catch (Exception e) {
            pendingResult = ToolRegistry.toolError("Tool execution failed: " + e.getMessage());
            toolOk = false;
        }
        recordToolCall(pendingTool, toolOk, System.currentTimeMillis() - toolStart);

        // Add the pending tool result to conversation
        agent.getConversationHistory().add(ModelMessage.tool(pendingResult, pendingTool.getId()));

        var cognitiveTraceCollector = agent.getCognitiveTraceCollector();
        if (cognitiveTraceCollector != null) {
            cognitiveTraceCollector.evaluate(cp.userTurnCount,
                "Tool " + pendingTool.getFunction().getName() + " " +
                    (approved ? "approved and executed" : "rejected") + ": " +
                    pendingResult.substring(0, Math.min(100, pendingResult.length())));
        }

        // Process remaining tool calls (after the pending one)
        for (int i = cp.pendingIndex + 1; i < cp.toolCalls.size(); i++) {
            ToolCall toolCall = cp.toolCalls.get(i);
            long tStart = System.currentTimeMillis();
            boolean tOk = true;
            String tResult;
            try {
                tResult = executeToolCall(toolCall);
            } catch (TenantAwareAIAgent.ToolApprovalRequiredException ex) {
                // Another tool needs approval - save new checkpoint
                approvalCheckpoint = new ToolApprovalCheckpoint(
                    cp.assistantMessage,
                    new ArrayList<>(cp.toolCalls),
                    i,
                    collectCompletedResults(cp, pendingResult, i),
                    cp.historySize + i, // already added pending + this will be next
                    cp.remainingIterations,
                    cp.userTurnCount,
                    cp.fromSubtask,
                    cp.subtask,
                    cp.subtaskMessage
                );
                approvalCheckpointActive = true;
                if (toolApprovalCallback != null) {
                    try { toolApprovalCallback.accept(ex); } catch (Exception ignored) {}
                }
                throw ex;
            } catch (RuntimeException ex) {
                tOk = false;
                throw ex;
            } finally {
                recordToolCall(toolCall, tOk, System.currentTimeMillis() - tStart);
            }
            agent.getConversationHistory().add(ModelMessage.tool(tResult, toolCall.getId()));

            if (cognitiveTraceCollector != null) {
                cognitiveTraceCollector.evaluate(cp.userTurnCount,
                    "Tool " + toolCall.getFunction().getName() + " returned: " +
                        tResult.substring(0, Math.min(100, tResult.length())));
            }
        }

        // Continue the LLM conversation loop
        return continueConversationLoop(responseBuilder, cp.userTurnCount, cp.remainingIterations);
    }

    /** Helper: collect all completed tool results up to current index. */
    private List<ToolCallResult> collectCompletedResults(ToolApprovalCheckpoint cp,
                                                          String pendingResult, int currentIndex) {
        List<ToolCallResult> results = new ArrayList<>(cp.completedResults);
        results.add(new ToolCallResult(cp.toolCalls.get(cp.pendingIndex).getId(), pendingResult));
        // Results for tools between pending+1 and currentIndex
        // These haven't been collected yet since we're at currentIndex which is the next approval
        return results;
    }

    /**
     * Continue the LLM conversation loop from the current state.
     * Delegates to AgentLoop.run() with a fresh budget.
     */
    private String continueConversationLoop(StringBuilder responseBuilder,
                                             int startTurnCount, int remainingIterations) {
        agent.setUserTurnCount(startTurnCount);

        // Create a fresh iteration budget with remaining iterations
        IterationBudget resumeBudget = new IterationBudget(remainingIterations);

        // Temporarily swap in the resume budget so AgentLoop uses it
        // Use reflection-free approach: AgentLoop reads ctx.budget() which reads agent.getIterationBudget()
        // We need to make the swap. Since iterationBudget is final, we use a different approach:
        // just run the loop with the original budget (it has the remaining iterations from before)
        var ctx = new com.nousresearch.hermes.harness.AgentContext(agent, agent.getConfig());
        String loopResponse = com.nousresearch.hermes.harness.AgentLoop.run(ctx, agent.getEventEmitter());

        if (responseBuilder.length() > 0 && !loopResponse.isEmpty()) {
            responseBuilder.append("\n\n");
        }
        responseBuilder.append(loopResponse);
        return responseBuilder.toString();
    }

    // ==================== Inner Classes ====================

    /**
     * Checkpoint state saved when a tool call requires approval.
     * Allows resuming execution from exactly where it left off.
     */
    static class ToolApprovalCheckpoint {
        /** The assistant message containing all tool calls */
        final ModelMessage assistantMessage;
        /** List of all tool calls from this assistant message */
        final List<ToolCall> toolCalls;
        /** Index of the tool call that triggered the approval */
        final int pendingIndex;
        /** Results of tool calls already executed (before the pending one) */
        final List<ToolCallResult> completedResults;
        /** Snapshot of conversation history at the point of the assistant message */
        final int historySize;
        /** The iteration budget state (remaining iterations) */
        final int remainingIterations;
        /** User turn count */
        final int userTurnCount;
        /** Whether we were in the processMessage call from handleIntentSubtask */
        final boolean fromSubtask;
        /** The original subtask description (for resuming subtask reply) */
        final String subtask;
        /** The original bus message (for replying when subtask completes) */
        final com.nousresearch.hermes.collaboration.AgentMessage subtaskMessage;

        ToolApprovalCheckpoint(ModelMessage assistantMessage, List<ToolCall> toolCalls,
                                int pendingIndex, List<ToolCallResult> completedResults,
                                int historySize, int remainingIterations, int userTurnCount,
                                boolean fromSubtask, String subtask,
                                com.nousresearch.hermes.collaboration.AgentMessage subtaskMessage) {
            this.assistantMessage = assistantMessage;
            this.toolCalls = toolCalls;
            this.pendingIndex = pendingIndex;
            this.completedResults = completedResults;
            this.historySize = historySize;
            this.remainingIterations = remainingIterations;
            this.userTurnCount = userTurnCount;
            this.fromSubtask = fromSubtask;
            this.subtask = subtask;
            this.subtaskMessage = subtaskMessage;
        }
    }

    /** Result of a single tool call (stored in checkpoint for completed ones) */
    static class ToolCallResult {
        final String toolCallId;
        final String result;
        ToolCallResult(String toolCallId, String result) {
            this.toolCallId = toolCallId;
            this.result = result;
        }
    }
}
