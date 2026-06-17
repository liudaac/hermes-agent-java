package com.nousresearch.hermes.business.approval;

import com.nousresearch.hermes.approval.ApprovalRequest;
import com.nousresearch.hermes.approval.ApprovalResult;
import com.nousresearch.hermes.approval.ApprovalSystem;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Projects foundation approvals into Business Portal approval cards.
 *
 * <p>The foundation ApprovalSystem remains the approval engine. Business
 * approval records are review/persistence projections for portal/mobile UX.</p>
 */
public class BusinessApprovalAdapter {

    public BusinessApprovalRecord fromApprovalRequest(String workspaceId, String teamId, ApprovalRequest request) {
        Objects.requireNonNull(request, "request");
        Instant now = Instant.now();
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("approvalType", request.getType().name());
        evidence.put("operation", request.getOperation());
        evidence.put("details", request.getDetails());
        evidence.put("dangerous", request.isDangerous());

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "foundation:approval-request");
        metadata.put("foundationApprovalType", request.getType().name());
        metadata.put("foundationOperation", request.getOperation());
        metadata.put("projectionKey", projectionKey(request));

        return new BusinessApprovalRecord()
            .setApprovalId("business-apv-" + shortHash(projectionKey(request)))
            .setWorkspaceId(workspaceId)
            .setTeamId(teamId)
            .setTitle(title(request))
            .setSummary(summary(request))
            .setReasonRequired(reasonRequired(request))
            .setApproveEffect(approveEffect(request))
            .setRejectEffect(rejectEffect(request))
            .setRecommendation(recommendation(request))
            .setRiskLevel(riskLevel(request))
            .setStatus(BusinessApprovalService.PENDING)
            .setEvidence(evidence)
            .setMetadata(metadata)
            .setCreatedAt(now)
            .setUpdatedAt(now);
    }

    /** Optionally persist a projected request through the existing business approval service. */
    public BusinessApprovalRecord persistRequest(BusinessApprovalService service, BusinessApprovalRecord projection) {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(projection, "projection");
        return service.createApproval(
            projection.getWorkspaceId(),
            projection.getTeamId(),
            projection.getTitle(),
            projection.getSummary(),
            projection.getReasonRequired(),
            projection.getApproveEffect(),
            projection.getRejectEffect(),
            projection.getRecommendation(),
            projection.getRiskLevel(),
            projection.getEvidence(),
            withProjectionMetadata(projection.getMetadata(), projection.getApprovalId())
        );
    }

    /**
     * Apply a foundation ApprovalResult to an in-memory card projection.
     * This mirrors foundation state for UX; it does not approve the foundation request.
     */
    public BusinessApprovalRecord withResult(BusinessApprovalRecord card, ApprovalResult result, String actor) {
        Objects.requireNonNull(card, "card");
        Objects.requireNonNull(result, "result");
        Instant now = Instant.now();
        Map<String, Object> metadata = new LinkedHashMap<>(card.getMetadata());
        metadata.put("foundationApprovalResult", result.toString());
        metadata.put("foundationSessionApproved", result.isSessionApproved());

        if (result.isApproved()) {
            card.setStatus(BusinessApprovalService.APPROVED)
                .setResolutionReason(result.isSessionApproved() ? "Approved by foundation for this session" : "Approved by foundation")
                .setResolvedBy(nonBlank(actor, "foundation-approval"));
        } else {
            card.setStatus(BusinessApprovalService.REJECTED)
                .setResolutionReason(nonBlank(result.getReason(), "Denied by foundation"))
                .setResolvedBy(nonBlank(actor, "foundation-approval"));
        }
        return card.setMetadata(metadata).setResolvedAt(now).setUpdatedAt(now);
    }

    /** Optionally resolve a persisted business card according to the foundation result. */
    public BusinessApprovalRecord resolvePersisted(BusinessApprovalService service, String workspaceId, String approvalId,
                                                   ApprovalResult result, String actor) {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(result, "result");
        if (result.isApproved()) {
            return service.approve(workspaceId, approvalId, nonBlank(actor, "foundation-approval"),
                result.isSessionApproved() ? "Approved by foundation for this session" : "Approved by foundation");
        }
        return service.reject(workspaceId, approvalId, nonBlank(actor, "foundation-approval"),
            nonBlank(result.getReason(), "Denied by foundation"));
    }

    private String title(ApprovalRequest request) {
        return switch (request.getType()) {
            case TERMINAL_COMMAND -> "Terminal command approval";
            case FILE_WRITE -> "File write approval";
            case FILE_DELETE -> "File delete approval";
            case CODE_EXECUTION -> "Code execution approval";
            case BROWSER_ACTION -> "Browser action approval";
            case SUBAGENT_SPAWN -> "Sub-agent spawn approval";
            case SKILL_INSTALL -> "Skill install approval";
        };
    }

    private String summary(ApprovalRequest request) {
        String details = request.getDetails() != null && !request.getDetails().isBlank()
            ? " Details: " + request.getDetails()
            : "";
        return "Hermes foundation requests approval for " + request.getType() + ": " + request.getOperation() + "." + details;
    }

    private String reasonRequired(ApprovalRequest request) {
        if (request.isDangerous()) {
            return "Foundation marked this operation as dangerous.";
        }
        return "Foundation ApprovalSystem requires confirmation for this operation type or policy mode.";
    }

    private String approveEffect(ApprovalRequest request) {
        return "Foundation operation may continue: " + request.getOperation();
    }

    private String rejectEffect(ApprovalRequest request) {
        return "Foundation operation will be denied or stopped: " + request.getOperation();
    }

    private String recommendation(ApprovalRequest request) {
        if (request.isDangerous()) {
            return "Review carefully; approve only with explicit operator confidence.";
        }
        return "Approve only if the operation matches the user's intent and policy.";
    }

    private String riskLevel(ApprovalRequest request) {
        if (request.isDangerous()) return "CRITICAL";
        ApprovalSystem.ApprovalType type = request.getType();
        if (type == null) return "MEDIUM";
        return switch (type) {
            case FILE_DELETE -> "CRITICAL";
            case TERMINAL_COMMAND, CODE_EXECUTION -> "HIGH";
            case FILE_WRITE, SKILL_INSTALL -> "MEDIUM";
            case BROWSER_ACTION, SUBAGENT_SPAWN -> "LOW";
        };
    }

    private String projectionKey(ApprovalRequest request) {
        return request.getType() + ":" + request.getOperation() + ":" + request.getDetails() + ":" + request.isDangerous();
    }

    private String shortHash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 10);
        } catch (Exception e) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private Map<String, Object> withProjectionMetadata(Map<String, Object> metadata, String projectionApprovalId) {
        Map<String, Object> copy = new LinkedHashMap<>();
        if (metadata != null) copy.putAll(metadata);
        copy.put("projectionApprovalId", projectionApprovalId);
        copy.put("projectionPersistedVia", "BusinessApprovalService");
        return copy;
    }

    private static String nonBlank(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }
}
