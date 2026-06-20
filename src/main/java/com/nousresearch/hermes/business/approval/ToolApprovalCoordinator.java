package com.nousresearch.hermes.business.approval;

import com.nousresearch.hermes.agent.TenantAwareAIAgent;
import com.nousresearch.hermes.workspace.WorkspaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Coordinates tool-level approvals between agent runtime and business approval service.
 *
 * <p>When an agent's tool call requires approval:
 * <ol>
 *   <li>Agent throws {@link TenantAwareAIAgent.ToolApprovalRequiredException}</li>
 *   <li>Agent saves a checkpoint internally (conversation state + pending tool call)</li>
 *   <li>This coordinator creates a {@link BusinessApprovalRecord}</li>
 *   <li>When user approves/rejects, this coordinator calls {@code agent.resumeToolApproval()}</li>
 * </ol>
 */
public class ToolApprovalCoordinator {

    private static final Logger logger = LoggerFactory.getLogger(ToolApprovalCoordinator.class);

    private final WorkspaceService workspaceService;
    private final BusinessApprovalService approvalService;

    /** approvalId → ToolApprovalContext */
    private final Map<String, ToolApprovalContext> pendingApprovals = new ConcurrentHashMap<>();

    public ToolApprovalCoordinator(WorkspaceService workspaceService, BusinessApprovalService approvalService) {
        this.workspaceService = workspaceService;
        this.approvalService = approvalService;
    }

    /**
     * Called by the agent when a tool call requires approval.
     * Creates a business approval record and stores context for later resume.
     *
     * @return the created approval ID
     */
    public String requestToolApproval(String workspaceId, String teamId, String agentId,
                                       String toolName, String toolArguments,
                                       String matchedRule, String reason,
                                       String toolCallId,
                                       TenantAwareAIAgent agent) {
        BusinessApprovalRecord approval = approvalService.createApproval(
            workspaceId,
            teamId,
            "工具调用审批: " + toolName,
            "Agent '" + agentId + "' 想要调用工具 '" + toolName + "'\n" +
                "原因: " + reason + "\n" +
                "参数: " + (toolArguments != null && toolArguments.length() < 500
                    ? toolArguments : "(参数过长，已省略)"),
            "请确认是否允许该工具调用。",
            "工具将被执行，agent 继续完成任务。",
            "工具调用被拒绝，agent 收到错误信息后会调整策略。",
            "请审查工具参数是否合理。",
            inferRiskLevel(toolName, matchedRule),
            Map.of(
                "toolName", toolName,
                "toolArguments", toolArguments != null ? toolArguments : "",
                "agentId", agentId,
                "matchedRule", matchedRule != null ? matchedRule : "",
                "reason", reason != null ? reason : "",
                "toolCallId", toolCallId != null ? toolCallId : ""
            ),
            Map.of(
                "source", "tool-approval",
                "type", "tool-call",
                "toolName", toolName
            )
        );

        // Store context for later resume
        ToolApprovalContext ctx = new ToolApprovalContext(
            approval.getApprovalId(), workspaceId, teamId, agentId,
            toolName, toolArguments, toolCallId, agent
        );
        pendingApprovals.put(approval.getApprovalId(), ctx);

        approval.addTimelineEntry("CREATED", "agent",
            "Agent " + agentId + " 申请工具调用审批: " + toolName,
            Map.of("toolName", toolName, "matchedRule", matchedRule != null ? matchedRule : ""));
        approvalService.updateApproval(approval);

        logger.info("Tool approval created: {} for agent {} tool {}",
            approval.getApprovalId(), agentId, toolName);

        return approval.getApprovalId();
    }

    /**
     * Resume agent execution after a tool approval decision.
     *
     * @return result from agent.resumeToolApproval(), or null if context not found
     */
    public String resumeToolApproval(String approvalId, boolean approved, String reason) {
        ToolApprovalContext ctx = pendingApprovals.remove(approvalId);
        if (ctx == null) {
            logger.warn("Tool approval context not found for {}", approvalId);
            return null;
        }

        try {
            String result = ctx.agent.resumeToolApproval(ctx.toolCallId, approved, reason);
            logger.info("Tool approval {} {} — agent {} resumed",
                approvalId, approved ? "approved" : "rejected", ctx.agentId);
            return result;
        } catch (TenantAwareAIAgent.ToolApprovalRequiredException nextApproval) {
            // Another tool needed approval after resume — already tracked via callback
            logger.info("Tool approval {} resumed but next tool '{}' needs approval",
                approvalId, nextApproval.getToolName());
            return "[Next tool approval pending: " + nextApproval.getToolName() + "]";
        } catch (Exception e) {
            logger.error("Failed to resume tool approval {}: {}", approvalId, e.getMessage(), e);
            return "[Resume failed: " + e.getMessage() + "]";
        }
    }

    /** Get context for a pending tool approval (for diagnostic purposes). */
    public ToolApprovalContext getContext(String approvalId) {
        return pendingApprovals.get(approvalId);
    }

    public boolean isPending(String approvalId) {
        return pendingApprovals.containsKey(approvalId);
    }

    private static String inferRiskLevel(String toolName, String matchedRule) {
        String lower = toolName.toLowerCase();
        if (lower.contains("delete") || lower.contains("payment") || lower.contains("refund")
            || lower.contains("transfer")) {
            return "HIGH";
        }
        if (lower.contains("send") || lower.contains("email") || lower.contains("post")
            || lower.contains("publish") || lower.contains("exec") || lower.contains("write")) {
            return "MEDIUM";
        }
        return "LOW";
    }

    /** Context for a pending tool approval — used to resume the agent later. */
    public static class ToolApprovalContext {
        public final String approvalId;
        public final String workspaceId;
        public final String teamId;
        public final String agentId;
        public final String toolName;
        public final String toolArguments;
        public final String toolCallId;
        public final TenantAwareAIAgent agent;

        ToolApprovalContext(String approvalId, String workspaceId, String teamId, String agentId,
                            String toolName, String toolArguments, String toolCallId,
                            TenantAwareAIAgent agent) {
            this.approvalId = approvalId;
            this.workspaceId = workspaceId;
            this.teamId = teamId;
            this.agentId = agentId;
            this.toolName = toolName;
            this.toolArguments = toolArguments;
            this.toolCallId = toolCallId;
            this.agent = agent;
        }
    }
}
