package com.nousresearch.hermes.business.analytics;

import com.nousresearch.hermes.business.approval.BusinessApprovalRecord;
import com.nousresearch.hermes.business.approval.BusinessApprovalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 审批分析引擎 — 检测审批效率瓶颈，辅助业务优化。
 *
 * <p>B端场景（如电商退款、大额订单审批）需要快速识别：
 * <ul>
 *   <li>平均审批耗时 — 是否存在审批积压</li>
 *   <li>通过率 — 审批策略是否过严或过松</li>
 *   <li>驳回原因分布 — 高频问题在哪里</li>
 *   <li>当前瓶颈 — 哪些审批单等待时间最长</li>
 * </ul>
 */
public class ApprovalAnalytics {
    private static final Logger logger = LoggerFactory.getLogger(ApprovalAnalytics.class);

    private final BusinessApprovalService approvalService;

    public ApprovalAnalytics(BusinessApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    /**
     * 平均审批耗时（分钟）— 从创建到最终决议的时间。
     * 过滤掉未完成的审批单，只统计已决议的。
     */
    public double getAverageResolutionTimeMinutes(String workspaceId) {
        List<BusinessApprovalRecord> resolved = approvalService.listApprovals(workspaceId, "ALL").stream()
            .filter(a -> a.getResolvedAt() != null && a.getCreatedAt() != null)
            .toList();

        if (resolved.isEmpty()) return 0.0;

        double totalMinutes = resolved.stream()
            .mapToDouble(a -> {
                Duration d = Duration.between(a.getCreatedAt(), a.getResolvedAt());
                return d.toMillis() / 60_000.0;
            })
            .sum();

        return Math.round(totalMinutes / resolved.size() * 10.0) / 10.0;
    }

    /** 审批通过率（approve / (approve + reject)） */
    public double getApprovalRate(String workspaceId) {
        List<BusinessApprovalRecord> all = approvalService.listApprovals(workspaceId, "ALL");
        long approved = all.stream().filter(a -> BusinessApprovalService.APPROVED.equals(a.getStatus())).count();
        long resolved = all.stream().filter(a ->
            BusinessApprovalService.APPROVED.equals(a.getStatus()) || BusinessApprovalService.REJECTED.equals(a.getStatus())
        ).count();
        return resolved == 0 ? 0.0 : Math.round(approved * 1000.0 / resolved) / 10.0;
    }

    /**
     * Distribution of rejection reasons.
     */
    public Map<String, Integer> getRejectionReasons(String workspaceId) {
        return approvalService.listApprovals(workspaceId, BusinessApprovalService.REJECTED).stream()
            .filter(a -> a.getResolutionReason() != null)
            .collect(Collectors.groupingBy(
                a -> categorizeReason(a.getResolutionReason()),
                Collectors.collectingAndThen(Collectors.counting(), Long::intValue)
            ));
    }

    /** 识别审批瓶颈 — 按等待时间降序排列当前待审批的单据 */
    public List<ApprovalBottleneck> getBottlenecks(String workspaceId, int limit) {
        return approvalService.listApprovals(workspaceId, BusinessApprovalService.PENDING).stream()
            .filter(a -> a.getCreatedAt() != null)
            .map(a -> {
                Duration pending = Duration.between(a.getCreatedAt(), Instant.now());
                return new ApprovalBottleneck(
                    a.getApprovalId(),
                    a.getTitle(),
                    a.getRiskLevel(),
                    pending.toMinutes(),
                    a.getCreatedAt()
                );
            })
            .sorted(Comparator.comparingLong(ApprovalBottleneck::pendingMinutes).reversed())
            .limit(limit > 0 ? limit : 10)
            .collect(Collectors.toList());
    }

    /** 完整看板摘要 — 供前端一次性拉取所有指标 */
    public Map<String, Object> getSummary(String workspaceId) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("avgResolutionTimeMinutes", getAverageResolutionTimeMinutes(workspaceId));
        summary.put("approvalRate", getApprovalRate(workspaceId));
        summary.put("rejectionReasons", getRejectionReasons(workspaceId));
        summary.put("topBottlenecks", getBottlenecks(workspaceId, 5));
        summary.put("pendingCount", approvalService.listApprovals(workspaceId, BusinessApprovalService.PENDING).size());
        summary.put("totalCount", approvalService.listApprovals(workspaceId, "ALL").size());
        return summary;
    }

    /** 将驳回原因文本归类到标准类别，用于聚合统计 */
    private String categorizeReason(String reason) {
        if (reason == null) return "Unknown";
        String lower = reason.toLowerCase();
        if (lower.contains("policy") || lower.contains("规则")) return "Policy Violation";
        if (lower.contains("risk") || lower.contains("风险")) return "Risk Concern";
        if (lower.contains("info") || lower.contains("信息") || lower.contains("补充")) return "Insufficient Info";
        if (lower.contains("budget") || lower.contains("成本") || lower.contains("预算")) return "Budget";
        return "Other";
    }

    /** 瓶颈记录 — 标识一个长时间未审批的单据 */
    public record ApprovalBottleneck(
        String approvalId,
        String title,
        String riskLevel,
        long pendingMinutes,
        Instant createdAt
    ) {}
}
