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

/**
 * 业务审批服务 — 管理 B 端用户的审批生命周期。
 *
 * <p>核心职责：
 * <ul>
 *   <li>创建审批请求（高风险或高价值业务动作触发）</li>
 *   <li>审批决议：通过、拒绝、要求补充信息</li>
 *   <li>持久化审批记录和审批时间线</li>
 *   <li>发布审批事件（支持全局、workspace 维度、单条审批维度订阅）</li>
 * </ul>
 * <p>审批记录按 workspace 隔离，存储在 <code>~/.hermes/business/workspaces/{workspaceId}/approvals/</code>。</p>
 */
public class BusinessApprovalService {
    // ---- 审批状态常量 ----
    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";
    public static final String INFO_REQUESTED = "INFO_REQUESTED";

    /** 审批记录持久化仓库 */
    private final FileBusinessApprovalRepository repository;
    /** 工作空间服务 — 用于校验 workspace 存在性 */
    private final WorkspaceService workspaceService;

    // ===== Event Bus =====
    /** 全局事件订阅者 — 接收所有 workspace 的所有审批事件 */
    private final List<Consumer<ApprovalEvent>> globalSubscribers = new CopyOnWriteArrayList<>();
    /** 按 workspace 隔离的订阅者 */
    private final Map<String, List<Consumer<ApprovalEvent>>> workspaceSubscribers = new ConcurrentHashMap<>();
    /** 按审批 ID 隔离的订阅者 — 用于 UI 实时跟踪单条审批 */
    private final Map<String, List<Consumer<ApprovalEvent>>> approvalSubscribers = new ConcurrentHashMap<>();

    /**
     * 默认构造函数 — 使用 ~/.hermes/business/workspaces 作为持久化根目录。
     *
     * @param workspaceService 工作空间服务
     */
    public BusinessApprovalService(WorkspaceService workspaceService) {
        this(new FileBusinessApprovalRepository(Constants.getHermesHome().resolve("business/workspaces")), workspaceService);
    }

    /**
     * 指定持久化根目录的构造函数。
     *
     * @param workspacesRoot   审批记录存储的根目录
     * @param workspaceService 工作空间服务
     */
    public BusinessApprovalService(Path workspacesRoot, WorkspaceService workspaceService) {
        this(new FileBusinessApprovalRepository(workspacesRoot), workspaceService);
    }

    /**
     * 完整构造函数，便于测试注入 Repository。
     *
     * @param repository       审批记录持久化仓库
     * @param workspaceService 工作空间服务
     */
    public BusinessApprovalService(FileBusinessApprovalRepository repository, WorkspaceService workspaceService) {
        this.repository = repository;
        this.workspaceService = workspaceService;
    }

    /** 创建审批请求 — 业务运行遇到高风险或高价值动作时调用。 */
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

    /** 列出审批列表 — 按状态过滤查询。 */
    public List<BusinessApprovalRecord> listApprovals(String workspaceId, String status) {
        if (workspaceId != null && !workspaceId.isBlank()) {
            workspaceService.requireWorkspace(workspaceId);
            return repository.list(workspaceId, normalizeStatusFilter(status));
        }
        List<String> workspaceIds = workspaceService.listWorkspaces().stream().map(WorkspaceRecord::getWorkspaceId).toList();
        return repository.listAll(workspaceIds, normalizeStatusFilter(status));
    }

    /** 获取审批，不存在时抛出异常。 */
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

    /**
     * 审批通过 — 仅允许对 PENDING 状态的审批执行。
     *
     * @param workspaceId 工作空间 ID
     * @param approvalId  审批 ID
     * @param actor       审批人标识
     * @param reason      审批理由（可为空）
     * @return 更新后的审批记录
     */
    public BusinessApprovalRecord approve(String workspaceId, String approvalId, String actor, String reason) {
        BusinessApprovalRecord record = requirePending(workspaceId, approvalId);
        resolve(record, APPROVED, actor, reason, null);
        return record;
    }

    /**
     * 审批拒绝 — 仅允许对 PENDING 状态的审批执行。
     *
     * @param workspaceId 工作空间 ID
     * @param approvalId  审批 ID
     * @param actor       审批人标识
     * @param reason      拒绝理由（可为空）
     * @return 更新后的审批记录
     */
    public BusinessApprovalRecord reject(String workspaceId, String approvalId, String actor, String reason) {
        BusinessApprovalRecord record = requirePending(workspaceId, approvalId);
        resolve(record, REJECTED, actor, reason, null);
        return record;
    }

    /**
     * 要求补充信息 — 将审批状态置为 INFO_REQUESTED，等待提交者补充后重新审批。
     *
     * @param workspaceId   工作空间 ID
     * @param approvalId    审批 ID
     * @param actor         审批人标识
     * @param requestedInfo 要求补充的信息内容
     * @return 更新后的审批记录
     */
    public BusinessApprovalRecord requestInfo(String workspaceId, String approvalId, String actor, String requestedInfo) {
        BusinessApprovalRecord record = requirePending(workspaceId, approvalId);
        resolve(record, INFO_REQUESTED, actor, "Requested additional information", requiredOrDefault(requestedInfo, "请补充更多业务依据。"));
        return record;
    }

    /** 获取审批并校验其状态必须为 PENDING，否则抛出异常。 */
    private BusinessApprovalRecord requirePending(String workspaceId, String approvalId) {
        BusinessApprovalRecord record = requireApproval(workspaceId, approvalId);
        if (!PENDING.equals(record.getStatus())) {
            throw new BusinessApprovalAlreadyResolvedException(workspaceId, approvalId, record.getStatus());
        }
        return record;
    }

    /** 统一处理审批决议 — 更新状态、时间线、持久化并发布事件。 */
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

    /** 当值为空时返回兜底文本。 */
    private static String requiredOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /** 将输入的风险等级标准化为已知枚举值，未知值回退到 LOW。 */
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

    /** 将前端传入的状态过滤值标准化；ALL 或空值表示不过滤。 */
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

    /** 按三层粒度（全局 → workspace → 单条审批）广播审批事件，订阅者异常被吞掉以保证广播不中断。 */
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

    /** 审批事件类型 — 对应审批生命周期中的关键节点。 */
    public enum ApprovalEventType {
        CREATED,
        APPROVED,
        REJECTED,
        INFO_REQUESTED,
        EXECUTION_RESUMED
    }

    /**
     * 审批事件 — 不可变记录，用于在事件总线中传递审批状态变更。
     *
     * @param approvalId  审批 ID
     * @param workspaceId 工作空间 ID
     * @param teamId      团队 ID
     * @param type        事件类型
     * @param title       审批标题
     * @param status      当前审批状态
     * @param actor       操作人
     * @param reason      操作理由
     * @param data        附加数据（通常为审批元数据）
     * @param timestamp   事件发生时间戳
     */
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

        /** 从审批记录构造事件实例。 */
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

    /** 审批记录不存在异常 — 用于统一 404 语义。 */
    public static class BusinessApprovalNotFoundException extends RuntimeException {
        public BusinessApprovalNotFoundException(String workspaceId, String approvalId) { super("Business approval not found: " + workspaceId + "/" + approvalId); }
    }

    /** 审批已决议异常 — 对非 PENDING 状态的审批执行 approve/reject/requestInfo 时抛出。 */
    public static class BusinessApprovalAlreadyResolvedException extends RuntimeException {
        public BusinessApprovalAlreadyResolvedException(String workspaceId, String approvalId, String status) { super("Business approval already resolved: " + workspaceId + "/" + approvalId + " status=" + status); }
    }
}
