package com.nousresearch.hermes.business.approval;

import com.nousresearch.hermes.config.Constants;
import com.nousresearch.hermes.workspace.WorkspaceRecord;
import com.nousresearch.hermes.workspace.WorkspaceService;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Business-facing approval center service. */
public class BusinessApprovalService {
    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";
    public static final String INFO_REQUESTED = "INFO_REQUESTED";

    private final FileBusinessApprovalRepository repository;
    private final WorkspaceService workspaceService;

    // ===== Event Bus =====
    private final List<Consumer<ApprovalEvent>> globalSubscribers = new CopyOnWriteArrayList<>();
    private final Map<String, List<Consumer<ApprovalEvent>>> workspaceSubscribers = new ConcurrentHashMap<>();
    private final Map<String, List<Consumer<ApprovalEvent>>> approvalSubscribers = new ConcurrentHashMap<>();

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
        record.addTimelineEntry("CREATED", "system", "审批创建", metadata);
        repository.save(record);
        publishEvent(ApprovalEvent.fromRecord(record, ApprovalEventType.CREATED, "system", "Approval created"));
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

    /** Save/update an approval record (for cross-reference updates after creation). */
    public BusinessApprovalRecord updateApproval(BusinessApprovalRecord record) {
        if (record == null || record.getApprovalId() == null) {
            throw new IllegalArgumentException("Approval record and approvalId are required");
        }
        workspaceService.requireWorkspace(record.getWorkspaceId());
        record.setUpdatedAt(Instant.now());
        repository.save(record);
        return record;
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

        // Add timeline entry
        String action = switch (status) {
            case APPROVED -> "APPROVED";
            case REJECTED -> "REJECTED";
            case INFO_REQUESTED -> "INFO_REQUESTED";
            default -> status;
        };
        String detail = switch (status) {
            case APPROVED -> "审批通过" + (reason != null && !reason.isBlank() ? "：" + reason : "");
            case REJECTED -> "审批拒绝" + (reason != null && !reason.isBlank() ? "：" + reason : "");
            case INFO_REQUESTED -> "要求补充信息" + (requestedInfo != null && !requestedInfo.isBlank() ? "：" + requestedInfo : "");
            default -> reason != null ? reason : "";
        };
        record.addTimelineEntry(action, record.getResolvedBy(), detail, null);

        repository.save(record);

        // Publish resolution event
        ApprovalEventType eventType = switch (status) {
            case APPROVED -> ApprovalEventType.APPROVED;
            case REJECTED -> ApprovalEventType.REJECTED;
            case INFO_REQUESTED -> ApprovalEventType.INFO_REQUESTED;
            default -> null;
        };
        if (eventType != null) {
            publishEvent(ApprovalEvent.fromRecord(record, eventType, record.getResolvedBy(), reason));
        }
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

    // ===== Event Bus Methods =====

    /** Subscribe to all approval events across all workspaces. */
    public void subscribeGlobal(Consumer<ApprovalEvent> subscriber) {
        globalSubscribers.add(subscriber);
    }

    /** Subscribe to approval events for a specific workspace. */
    public void subscribeWorkspace(String workspaceId, Consumer<ApprovalEvent> subscriber) {
        workspaceSubscribers.computeIfAbsent(workspaceId, k -> new CopyOnWriteArrayList<>()).add(subscriber);
    }

    /** Subscribe to events for a specific approval. */
    public void subscribeApproval(String workspaceId, String approvalId, Consumer<ApprovalEvent> subscriber) {
        approvalSubscribers.computeIfAbsent(approvalId, k -> new CopyOnWriteArrayList<>()).add(subscriber);
    }

    /** Unsubscribe from a specific approval. */
    public void unsubscribeApproval(String approvalId, Consumer<ApprovalEvent> subscriber) {
        var subs = approvalSubscribers.get(approvalId);
        if (subs != null) {
            subs.remove(subscriber);
            if (subs.isEmpty()) {
                approvalSubscribers.remove(approvalId);
            }
        }
    }

    private void publishEvent(ApprovalEvent event) {
        // Global subscribers
        for (var sub : globalSubscribers) {
            try { sub.accept(event); } catch (Exception e) { /* ignore */ }
        }
        // Workspace subscribers
        var wsSubs = workspaceSubscribers.get(event.workspaceId());
        if (wsSubs != null) {
            for (var sub : wsSubs) {
                try { sub.accept(event); } catch (Exception e) { /* ignore */ }
            }
        }
        // Approval-specific subscribers
        var apvSubs = approvalSubscribers.get(event.approvalId());
        if (apvSubs != null) {
            for (var sub : apvSubs) {
                try { sub.accept(event); } catch (Exception e) { /* ignore */ }
            }
        }
    }

    // ===== Event Types =====

    public enum ApprovalEventType {
        CREATED,
        APPROVED,
        REJECTED,
        INFO_REQUESTED,
        EXECUTION_RESUMED
    }

    public record ApprovalEvent(
        String approvalId,
        String workspaceId,
        String teamId,
        ApprovalEventType type,
        String title,
        String status,
        String actor,
        String reason,
        Map<String, Object> data,
        long timestamp
    ) {
        public ApprovalEvent(String approvalId, String workspaceId, String teamId, ApprovalEventType type,
                             String title, String status, String actor, String reason, Map<String, Object> data) {
            this(approvalId, workspaceId, teamId, type, title, status, actor, reason, data, System.currentTimeMillis());
        }

        static ApprovalEvent fromRecord(BusinessApprovalRecord record, ApprovalEventType type, String actor, String reason) {
            return new ApprovalEvent(
                record.getApprovalId(),
                record.getWorkspaceId(),
                record.getTeamId(),
                type,
                record.getTitle(),
                record.getStatus(),
                actor,
                reason,
                record.getMetadata() != null ? record.getMetadata() : Map.of()
            );
        }
    }

    public static class BusinessApprovalNotFoundException extends RuntimeException {
        public BusinessApprovalNotFoundException(String workspaceId, String approvalId) { super("Business approval not found: " + workspaceId + "/" + approvalId); }
    }

    public static class BusinessApprovalAlreadyResolvedException extends RuntimeException {
        public BusinessApprovalAlreadyResolvedException(String workspaceId, String approvalId, String status) { super("Business approval already resolved: " + workspaceId + "/" + approvalId + " status=" + status); }
    }
}
