package com.nousresearch.hermes.dashboard.jarvis;

import com.nousresearch.hermes.business.approval.BusinessApprovalRecord;
import com.nousresearch.hermes.business.approval.BusinessApprovalService;
import com.nousresearch.hermes.business.approval.ToolApprovalCoordinator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ApprovalBridge — single entry point that routes Jarvis approval
 * resolutions to whichever subsystem owns the decision.
 *
 * <p>There are two kinds of approvals that come through the
 * {@code /api/jarvis/approval/{id}} endpoint:</p>
 *
 * <ol>
 *   <li><b>Jarvis-initiated</b> approvals — created by Jarvis when it
 *       wants to do a top-level dangerous action (no agent in the loop).
 *       The decision is owned by {@link BusinessApprovalService}, which
 *       persists the record, fires the event bus, and surfaces it in the
 *       Portal inbox.</li>
 *   <li><b>Tool-level</b> approvals — created when a
 *       {@code TenantAwareAIAgent} hits a tool approval rule during
 *       {@code processMessage()}. The agent is paused, and the
 *       {@link ToolApprovalCoordinator} holds a reference to it. When
 *       the user resolves, we call
 *       {@link ToolApprovalCoordinator#resumeToolApproval}, which
 *       invokes {@code agent.resumeToolApproval()} and returns the
 *       agent's continuation output.</li>
 * </ol>
 *
 * <p>This class figures out which path to take by looking up
 * {@code coordinator.isPending(id)} first (fast check), and falling
 * back to the local metadata map for Jarvis-initiated approvals.</p>
 */
public class ApprovalBridge {
    private static final Logger log = LoggerFactory.getLogger(ApprovalBridge.class);

    public enum Decision { PENDING, APPROVED, REJECTED, INFO_REQUESTED }

    /** Result of resolving an approval. */
    public record ResolutionResult(
        boolean ok,
        String approvalId,
        Decision decision,
        /** Agent continuation output (for tool-level approvals), or null. */
        String reply
    ) {}

    /** Per-approval metadata we keep for Jarvis-initiated approvals. */
    public record PendingApproval(
        String id,
        String workspaceId,
        String teamId,
        String title,
        String description,
        String riskLevel,
        long createdAt
    ) {}

    private final BusinessApprovalService approvalService;
    private final ToolApprovalCoordinator toolApprovalCoordinator;
    private final Map<String, PendingApproval> metadata = new ConcurrentHashMap<>();

    public ApprovalBridge(BusinessApprovalService approvalService,
                          ToolApprovalCoordinator toolApprovalCoordinator) {
        this.approvalService = approvalService;
        this.toolApprovalCoordinator = toolApprovalCoordinator;
    }

    /**
     * Create a Jarvis-initiated approval. Delegates to
     * BusinessApprovalService so the approval is persisted, appears in
     * the Portal inbox, and fires the global/workspace event bus.
     */
    public String request(String workspaceId, String teamId,
                          String title, String description, String riskLevel) {
        String normalizedRisk = normalizeRisk(riskLevel);
        String safeTitle = (title == null || title.isBlank()) ? "Jarvis action" : title;
        String safeDesc = (description == null) ? "" : description;
        String safeWorkspace = (workspaceId == null || workspaceId.isBlank())
            ? "default" : workspaceId;

        BusinessApprovalRecord record = approvalService.createApproval(
            safeWorkspace,
            teamId,
            safeTitle,
            safeDesc,
            "Jarvis 请求执行该动作。请确认是否符合业务意图。",
            "动作将被执行，Jarvis 继续后续任务。",
            "动作被拒绝，Jarvis 收到驳回信息后调整策略。",
            "请确认动作的范围与影响面是否在可接受范围内。",
            normalizedRisk,
            Map.of(
                "source", "jarvis",
                "title", safeTitle
            ),
            Map.of(
                "source", "jarvis",
                "initiator", "jarvis-dialogue-shell"
            )
        );

        metadata.put(record.getApprovalId(), new PendingApproval(
            record.getApprovalId(),
            safeWorkspace,
            teamId,
            safeTitle,
            safeDesc,
            normalizedRisk,
            System.currentTimeMillis()
        ));

        log.info("Jarvis approval {} created in workspace {} (risk={}, title={})",
            record.getApprovalId(), safeWorkspace, normalizedRisk, safeTitle);
        return record.getApprovalId();
    }

    /**
     * Resolve a pending approval. Routes to either the tool-level
     * coordinator (if the approval is for a paused agent tool call) or
     * the business approval service (if the approval is Jarvis-initiated).
     *
     * @return the resolution result, including the agent's continuation
     *         output for tool-level approvals
     */
    public ResolutionResult resolve(String approvalId, String decisionStr) {
        if (approvalId == null || approvalId.isBlank()) {
            return new ResolutionResult(false, approvalId, Decision.PENDING, null);
        }
        Decision d = parseDecision(decisionStr);

        // Tool-level approvals live in the coordinator's pendingApprovals map.
        if (toolApprovalCoordinator != null && toolApprovalCoordinator.isPending(approvalId)) {
            boolean approved = (d == Decision.APPROVED);
            String reply = null;
            try {
                reply = toolApprovalCoordinator.resumeToolApproval(
                    approvalId, approved, "通过 Jarvis 浮窗决议");
            } catch (Exception e) {
                log.error("Failed to resume tool approval {}: {}", approvalId, e.getMessage(), e);
                return new ResolutionResult(false, approvalId, Decision.PENDING,
                    "（resume 失败：" + e.getMessage() + "）");
            }
            log.info("Jarvis tool-approval {} {} via dashboard; agent reply length={}",
                approvalId, approved ? "approved" : "rejected",
                reply == null ? 0 : reply.length());
            return new ResolutionResult(true, approvalId, d, reply);
        }

        // Otherwise it's a Jarvis-initiated approval — resolve via the
        // business approval service.
        PendingApproval md = metadata.get(approvalId);
        if (md == null) {
            log.warn("Jarvis resolveApproval called with unknown approvalId={} (decision={})",
                approvalId, decisionStr);
            return new ResolutionResult(false, approvalId, d, null);
        }
        try {
            switch (d) {
                case APPROVED -> approvalService.approve(
                    md.workspaceId, approvalId, "jarvis-user", "通过浮窗批准");
                case REJECTED -> approvalService.reject(
                    md.workspaceId, approvalId, "jarvis-user", "通过浮窗驳回");
                case INFO_REQUESTED -> approvalService.requestInfo(
                    md.workspaceId, approvalId, "jarvis-user", "Jarvis 已请求补充信息");
                default -> {
                    // PENDING shouldn't be sent by the front-end; ignore.
                }
            }
            log.info("Jarvis business-approval {} {} via dashboard",
                approvalId, d);
        } catch (Exception e) {
            log.error("Failed to resolve Jarvis approval {}: {}", approvalId, e.getMessage(), e);
            return new ResolutionResult(false, approvalId, d, null);
        }
        return new ResolutionResult(true, approvalId, d, null);
    }

    /** Read the current decision for a Jarvis-initiated approval, if known. */
    public Decision checkDecision(String approvalId) {
        if (approvalId == null) return Decision.PENDING;
        PendingApproval md = metadata.get(approvalId);
        if (md == null) return Decision.PENDING;
        try {
            BusinessApprovalRecord rec = approvalService.requireApproval(md.workspaceId, approvalId);
            return switch (rec.getStatus()) {
                case BusinessApprovalService.APPROVED -> Decision.APPROVED;
                case BusinessApprovalService.REJECTED -> Decision.REJECTED;
                case BusinessApprovalService.INFO_REQUESTED -> Decision.INFO_REQUESTED;
                default -> Decision.PENDING;
            };
        } catch (Exception e) {
            return Decision.PENDING;
        }
    }

    /** Snapshot the metadata for diagnostics / Portal display. */
    public PendingApproval get(String approvalId) {
        return metadata.get(approvalId);
    }

    private static Decision parseDecision(String s) {
        if (s == null) return Decision.REJECTED;
        return switch (s.toLowerCase()) {
            case "approve", "approved" -> Decision.APPROVED;
            case "reject", "rejected" -> Decision.REJECTED;
            case "info", "info_requested", "info-requested" -> Decision.INFO_REQUESTED;
            default -> Decision.REJECTED;
        };
    }

    private static String normalizeRisk(String s) {
        if (s == null) return "MEDIUM";
        return switch (s.toLowerCase()) {
            case "low", "l" -> "LOW";
            case "high", "h" -> "HIGH";
            default -> "MEDIUM";
        };
    }
}
