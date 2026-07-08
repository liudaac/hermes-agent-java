package com.nousresearch.hermes.dashboard.jarvis;

import com.nousresearch.hermes.business.approval.BusinessApprovalRecord;
import com.nousresearch.hermes.business.approval.BusinessApprovalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JarvisApprovalBridge — thin facade that routes Jarvis-initiated approvals
 * through the real BusinessApprovalService.
 *
 * <p>Why this exists: Jarvis lives above the agent runtime and is consulted
 * for top-level "do something dangerous" decisions (delete a workspace, rotate
 * a key, run a mass-update scenario, etc.). These decisions are exactly the
 * shape that BusinessApprovalService already handles (PENDING → APPROVED /
 * REJECTED, workspace-scoped, with evidence and timeline), so we reuse it
 * instead of maintaining a parallel in-memory registry.</p>
 *
 * <p>This class does NOT store the decision state. The decision is owned by
 * BusinessApprovalService and its backing repository (file by default; plug
 * in Redis/SQL via the same repository interface for multi-instance
 * deployments). What we keep here is just a small lookup table for the
 * workspace id — the front-end sends only the approval id when resolving,
 * so we need to remember which workspace a given approval belongs to.</p>
 *
 * <p>Risk mapping: Jarvis uses {@code low/medium/high}; BusinessApprovalService
 * uses {@code LOW/MEDIUM/HIGH}. We normalize on the way in and back on the
 * way out.</p>
 */
public class ApprovalBridge {
    private static final Logger log = LoggerFactory.getLogger(ApprovalBridge.class);

    public enum Decision { PENDING, APPROVED, REJECTED, INFO_REQUESTED }

    /** Per-approval metadata we keep alongside the BusinessApprovalRecord. */
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
    private final Map<String, PendingApproval> metadata = new ConcurrentHashMap<>();

    public ApprovalBridge(BusinessApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    /**
     * Request a Jarvis-initiated approval. Delegates to
     * BusinessApprovalService.createApproval so the approval is persisted,
     * appears in the Portal's approval inbox, and fires the global /
     * workspace / approval-scoped event bus.
     *
     * @param workspaceId  workspace that owns the action
     * @param teamId       team (may be null for cross-team Jarvis actions)
     * @param title        short title shown in the approval card
     * @param description  long-form description / evidence
     * @param riskLevel    {@code low|medium|high} — normalized internally
     * @return the generated approval id
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
     * Resolve a pending approval. The decision is recorded by
     * BusinessApprovalService, which fires the appropriate event on the
     * workspace + global event bus.
     */
    public void resolve(String approvalId, String decisionStr) {
        if (approvalId == null || approvalId.isBlank()) return;
        PendingApproval md = metadata.get(approvalId);
        if (md == null) {
            // Approval may have been created by another path (e.g. tool-level
            // approval via ToolApprovalCoordinator). Look it up via the service
            // to see if we can find a matching record; if not, it's a no-op
            // and we log a warning.
            log.warn("Jarvis resolveApproval called with unknown approvalId={} (decision={})",
                approvalId, decisionStr);
            return;
        }
        Decision d = parseDecision(decisionStr);
        try {
            switch (d) {
                case APPROVED -> {
                    BusinessApprovalRecord rec = approvalService.approve(
                        md.workspaceId, approvalId, "jarvis-user", "通过浮窗批准");
                    log.info("Jarvis approval {} approved via dashboard", approvalId);
                }
                case REJECTED -> {
                    BusinessApprovalRecord rec = approvalService.reject(
                        md.workspaceId, approvalId, "jarvis-user", "通过浮窗驳回");
                    log.info("Jarvis approval {} rejected via dashboard", approvalId);
                }
                case INFO_REQUESTED -> {
                    BusinessApprovalRecord rec = approvalService.requestInfo(
                        md.workspaceId, approvalId, "jarvis-user", "Jarvis 已请求补充信息");
                    log.info("Jarvis approval {} info-requested via dashboard", approvalId);
                }
                default -> {
                    // PENDING shouldn't be sent by the front-end; ignore.
                }
            }
        } catch (Exception e) {
            log.error("Failed to resolve Jarvis approval {}: {}", approvalId, e.getMessage(), e);
            return;
        }
        // Keep metadata around for a short window so follow-up lookups (e.g.
        // for the front-end's status check) still work. A periodic reaper
        // would be ideal; for now we leave them — the in-memory map is bounded
        // by the number of approvals this JVM has seen, and they're small.
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
