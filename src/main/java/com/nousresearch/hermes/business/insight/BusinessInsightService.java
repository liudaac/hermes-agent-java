package com.nousresearch.hermes.business.insight;

import com.nousresearch.hermes.blueprint.TeamBlueprintService;
import com.nousresearch.hermes.business.approval.BusinessApprovalRecord;
import com.nousresearch.hermes.business.approval.BusinessApprovalService;
import com.nousresearch.hermes.business.run.BusinessRunRecord;
import com.nousresearch.hermes.business.run.BusinessRunService;
import com.nousresearch.hermes.workspace.WorkspaceRecord;
import com.nousresearch.hermes.workspace.WorkspaceService;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Generates lightweight business insights from existing Business Portal data. */
public class BusinessInsightService {
    private final WorkspaceService workspaceService;
    private final TeamBlueprintService teamBlueprintService;
    private final BusinessRunService runService;
    private final BusinessApprovalService approvalService;

    public BusinessInsightService(WorkspaceService workspaceService,
                                  TeamBlueprintService teamBlueprintService,
                                  BusinessRunService runService,
                                  BusinessApprovalService approvalService) {
        this.workspaceService = workspaceService;
        this.teamBlueprintService = teamBlueprintService;
        this.runService = runService;
        this.approvalService = approvalService;
    }

    public BusinessInsightSummary summarize(String workspaceId) {
        Instant now = Instant.now();
        List<WorkspaceRecord> workspaces = workspaceId == null || workspaceId.isBlank()
            ? workspaceService.listWorkspaces()
            : workspaceService.getWorkspace(workspaceId).map(List::of).orElseThrow(() -> new WorkspaceService.WorkspaceNotFoundException(workspaceId));

        int teamCount = 0;
        List<BusinessRunRecord> runs = new ArrayList<>();
        List<BusinessApprovalRecord> pendingApprovals = new ArrayList<>();
        for (WorkspaceRecord workspace : workspaces) {
            teamCount += teamBlueprintService.listTeamBlueprints(workspace.getWorkspaceId()).size();
            runs.addAll(runService.listRuns(workspace.getWorkspaceId(), null, null, 0));
            pendingApprovals.addAll(approvalService.listApprovals(workspace.getWorkspaceId(), BusinessApprovalService.PENDING));
        }

        int failedRunCount = (int) runs.stream().filter(run -> BusinessRunService.FAILED.equals(run.getStatus())).count();
        int needsApprovalRunCount = (int) runs.stream().filter(run -> BusinessRunService.NEEDS_APPROVAL.equals(run.getStatus())).count();
        int highRiskApprovalCount = (int) pendingApprovals.stream()
            .filter(approval -> "HIGH".equals(approval.getRiskLevel()) || "CRITICAL".equals(approval.getRiskLevel()))
            .count();
        double failureRate = runs.isEmpty() ? 0.0 : Math.round((failedRunCount * 1000.0 / runs.size())) / 10.0;

        BusinessInsightSummary summary = new BusinessInsightSummary()
            .setWorkspaceId(workspaceId == null ? "" : workspaceId)
            .setWorkspaceCount(workspaces.size())
            .setTeamCount(teamCount)
            .setRunCount(runs.size())
            .setFailedRunCount(failedRunCount)
            .setNeedsApprovalRunCount(needsApprovalRunCount)
            .setPendingApprovalCount(pendingApprovals.size())
            .setHighRiskApprovalCount(highRiskApprovalCount)
            .setFailureRate(failureRate)
            .setGeneratedAt(now);

        summary.setInsights(generateInsights(summary, now));
        summary.setNextActions(generateNextActions(summary));
        return summary;
    }

    private List<BusinessInsightRecord> generateInsights(BusinessInsightSummary summary, Instant now) {
        List<BusinessInsightRecord> insights = new ArrayList<>();
        Map<String, Object> metrics = summary.metricsMap();

        if (summary.getWorkspaceCount() == 0) {
            insights.add(insight("insight-create-workspace", summary.getWorkspaceId(), "还没有业务空间",
                "系统尚未创建业务空间，Business Portal 还没有可分析的数据。",
                "业务入口尚未初始化。",
                "先创建一个业务空间，再创建第一个智能体团队。",
                "建立后续团队、运行、审批和洞察的数据基础。",
                "create-workspace", "INFO", metrics, now));
            return insights;
        }

        if (summary.getTeamCount() == 0) {
            insights.add(insight("insight-create-team", summary.getWorkspaceId(), "还没有智能体团队",
                "已有业务空间，但还没有可运行的智能体团队。",
                "尚未把业务场景转成团队蓝图。",
                "创建第一个 Team Blueprint，并用样例任务试运行。",
                "让业务用户能验证岗位分工和运行解释是否合理。",
                "create-team-blueprint", "INFO", metrics, now));
        }

        if (summary.getRunCount() == 0 && summary.getTeamCount() > 0) {
            insights.add(insight("insight-run-sample", summary.getWorkspaceId(), "还没有运行记录",
                "团队已经创建，但还没有业务运行样本。",
                "尚未进行样例任务试运行或真实任务接入。",
                "先创建 3-5 条典型样例运行记录，验证业务故事化 Trace。",
                "提前发现岗位边界、知识缺口和审批规则问题。",
                "run-sample-tasks", "INFO", metrics, now));
        }

        if (summary.getFailedRunCount() > 0) {
            String severity = summary.getFailureRate() >= 20.0 ? "HIGH" : "MEDIUM";
            insights.add(insight("insight-run-failures", summary.getWorkspaceId(), "存在失败运行记录",
                "当前共有 " + summary.getFailedRunCount() + " 条失败运行，失败率约 " + summary.getFailureRate() + "% 。",
                "可能存在知识缺口、工具调用失败或团队分工不清。",
                "按失败场景复盘运行步骤，并生成新的团队蓝图草案。",
                "降低人工纠正率，提升自动完成率。",
                "review-failed-runs", severity, metrics, now));
        }

        if (summary.getPendingApprovalCount() > 0) {
            String severity = summary.getHighRiskApprovalCount() > 0 ? "HIGH" : "MEDIUM";
            insights.add(insight("insight-pending-approvals", summary.getWorkspaceId(), "存在待审批事项",
                "当前有 " + summary.getPendingApprovalCount() + " 条待审批，其中高风险 " + summary.getHighRiskApprovalCount() + " 条。",
                "系统遇到了需要人类确认的动作或版本发布。",
                "优先处理高风险审批，并检查是否需要调整审批边界。",
                "减少阻塞时间，同时保持高风险动作可控。",
                "review-approvals", severity, metrics, now));
        }

        if (insights.isEmpty()) {
            insights.add(insight("insight-healthy-baseline", summary.getWorkspaceId(), "当前业务闭环健康",
                "目前没有失败运行和待审批阻塞。",
                "系统处于基础稳定状态。",
                "继续积累运行样本；达到 20 条后再分析趋势和版本优化机会。",
                "用真实数据驱动下一版团队进化。",
                "collect-more-runs", "INFO", metrics, now));
        }
        return insights;
    }

    private List<Map<String, Object>> generateNextActions(BusinessInsightSummary summary) {
        List<Map<String, Object>> actions = new ArrayList<>();
        if (summary.getWorkspaceCount() == 0) actions.add(action("create-workspace", "创建业务空间", "初始化 Business Portal 数据基础"));
        else if (summary.getTeamCount() == 0) actions.add(action("create-team-blueprint", "创建智能体团队", "把第一个业务场景转成团队蓝图"));
        else if (summary.getRunCount() == 0) actions.add(action("run-sample-tasks", "创建样例运行", "验证业务故事化 Trace 是否可读"));
        if (summary.getPendingApprovalCount() > 0) actions.add(action("review-approvals", "处理待审批", "优先处理高风险审批卡"));
        if (summary.getFailedRunCount() > 0) actions.add(action("review-failed-runs", "复盘失败运行", "定位知识缺口或流程问题"));
        if (actions.isEmpty()) actions.add(action("collect-more-runs", "继续积累运行样本", "达到 20 条后生成趋势分析"));
        return actions;
    }

    private BusinessInsightRecord insight(String id, String workspaceId, String title, String finding,
                                          String possibleCause, String recommendation, String expectedBenefit,
                                          String suggestedAction, String severity, Map<String, Object> metrics, Instant now) {
        return new BusinessInsightRecord()
            .setInsightId(id)
            .setWorkspaceId(workspaceId == null ? "" : workspaceId)
            .setTitle(title)
            .setFinding(finding)
            .setPossibleCause(possibleCause)
            .setRecommendation(recommendation)
            .setExpectedBenefit(expectedBenefit)
            .setSuggestedAction(suggestedAction)
            .setSeverity(severity)
            .setMetrics(metrics)
            .setGeneratedAt(now);
    }

    private Map<String, Object> action(String id, String title, String description) {
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("id", id);
        action.put("title", title);
        action.put("description", description);
        return action;
    }
}
