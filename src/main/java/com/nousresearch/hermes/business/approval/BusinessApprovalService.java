package com.nousresearch.hermes.business.approval;

import com.nousresearch.hermes.config.Constants;
import com.nousresearch.hermes.workspace.WorkspaceRecord;
import com.nousresearch.hermes.workspace.WorkspaceService;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Business-facing approval center service. */
public class BusinessApprovalService {
    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";
    public static final String INFO_REQUESTED = "INFO_REQUESTED";

    private final FileBusinessApprovalRepository repository;
    private final WorkspaceService workspaceService;

    public BusinessApprovalService(WorkspaceService workspaceService) {
        this(new FileBusinessApprovalRepository(Constants.getHermesHome().resolve("business/workspaces")), workspaceService);
    }

    public BusinessApprovalService(Path workspacesRoot, WorkspaceService workspaceService) {
        this(new FileBusinessApprovalRepository(workspacesRoot), workspaceService);
    }

    public BusinessApprovalService(FileBusinessApprovalRepository repository, WorkspaceService workspaceService) {
        this.repository = repository;
        this.workspaceService = workspaceService;
    }

    public BusinessApprovalRecord createApproval(String workspaceId, String teamId, String title, String summary,
                                                 String reasonRequired, String approveEffect, String rejectEffect,
                                                 String recommendation, String riskLevel,
                                                 Map<String, Object> evidence, Map<String, Object> metadata) {
        workspaceService.requireWorkspace(workspaceId);
        Instant now = Instant.now();
        BusinessApprovalRecord record = new BusinessApprovalRecord()
            .setApprovalId("apv-" + UUID.randomUUID().toString().substring(0, 10))
            .setWorkspaceId(workspaceId)
            .setTeamId(teamId)
            .setTitle(requiredOrDefault(title, "待审批事项"))
            .setSummary(requiredOrDefault(summary, "请确认该业务动作是否可以继续。"))
            .setReasonRequired(reasonRequired)
            .setApproveEffect(approveEffect)
            .setRejectEffect(rejectEffect)
            .setRecommendation(recommendation)
            .setRiskLevel(normalizeRisk(riskLevel))
            .setStatus(PENDING)
            .setEvidence(evidence)
            .setMetadata(metadata)
            .setCreatedAt(now)
            .setUpdatedAt(now);
        repository.save(record);
        return record;
    }

    public List<BusinessApprovalRecord> listApprovals(String workspaceId, String status) {
        if (workspaceId != null && !workspaceId.isBlank()) {
            workspaceService.requireWorkspace(workspaceId);
            return repository.list(workspaceId, normalizeStatusFilter(status));
        }
        List<String> workspaceIds = workspaceService.listWorkspaces().stream().map(WorkspaceRecord::getWorkspaceId).toList();
        return repository.listAll(workspaceIds, normalizeStatusFilter(status));
    }

    public BusinessApprovalRecord requireApproval(String workspaceId, String approvalId) {
        workspaceService.requireWorkspace(workspaceId);
        return repository.findById(workspaceId, approvalId)
            .orElseThrow(() -> new BusinessApprovalNotFoundException(workspaceId, approvalId));
    }

    public BusinessApprovalRecord approve(String workspaceId, String approvalId, String actor, String reason) {
        BusinessApprovalRecord record = requirePending(workspaceId, approvalId);
        resolve(record, APPROVED, actor, reason, null);
        return record;
    }

    public BusinessApprovalRecord reject(String workspaceId, String approvalId, String actor, String reason) {
        BusinessApprovalRecord record = requirePending(workspaceId, approvalId);
        resolve(record, REJECTED, actor, reason, null);
        return record;
    }

    public BusinessApprovalRecord requestInfo(String workspaceId, String approvalId, String actor, String requestedInfo) {
        BusinessApprovalRecord record = requirePending(workspaceId, approvalId);
        resolve(record, INFO_REQUESTED, actor, "Requested additional information", requiredOrDefault(requestedInfo, "请补充更多业务依据。"));
        return record;
    }

    private BusinessApprovalRecord requirePending(String workspaceId, String approvalId) {
        BusinessApprovalRecord record = requireApproval(workspaceId, approvalId);
        if (!PENDING.equals(record.getStatus())) {
            throw new BusinessApprovalAlreadyResolvedException(workspaceId, approvalId, record.getStatus());
        }
        return record;
    }

    private void resolve(BusinessApprovalRecord record, String status, String actor, String reason, String requestedInfo) {
        Instant now = Instant.now();
        record.setStatus(status)
            .setResolvedBy(actor != null && !actor.isBlank() ? actor : "business-user")
            .setResolutionReason(reason)
            .setRequestedInfo(requestedInfo)
            .setResolvedAt(now)
            .setUpdatedAt(now);
        repository.save(record);
    }

    private static String requiredOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String normalizeRisk(String riskLevel) {
        if (riskLevel == null || riskLevel.isBlank()) {
            return "LOW";
        }
        String normalized = riskLevel.trim().toUpperCase();
        return switch (normalized) {
            case "LOW", "MEDIUM", "HIGH", "CRITICAL" -> normalized;
            default -> "LOW";
        };
    }

    private static String normalizeStatusFilter(String status) {
        if (status == null || status.isBlank() || "ALL".equalsIgnoreCase(status)) {
            return null;
        }
        return status.trim().toUpperCase();
    }

    public static class BusinessApprovalNotFoundException extends RuntimeException {
        public BusinessApprovalNotFoundException(String workspaceId, String approvalId) { super("Business approval not found: " + workspaceId + "/" + approvalId); }
    }

    public static class BusinessApprovalAlreadyResolvedException extends RuntimeException {
        public BusinessApprovalAlreadyResolvedException(String workspaceId, String approvalId, String status) { super("Business approval already resolved: " + workspaceId + "/" + approvalId + " status=" + status); }
    }
}
