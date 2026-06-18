package com.nousresearch.hermes.business.insight;

import com.nousresearch.hermes.blueprint.TeamBlueprintService;
import com.nousresearch.hermes.business.approval.BusinessApprovalRecord;
import com.nousresearch.hermes.business.approval.BusinessApprovalService;
import com.nousresearch.hermes.business.run.BusinessRunRecord;
import com.nousresearch.hermes.business.run.BusinessRunService;
import com.nousresearch.hermes.workspace.WorkspaceRecord;
import com.nousresearch.hermes.workspace.WorkspaceService;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

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
        return summarize(workspaceId, null);
    }

    public BusinessInsightSummary summarize(String workspaceId, String scenarioId) {
        Instant now = Instant.now();
        List<WorkspaceRecord> workspaces = workspaceId == null || workspaceId.isBlank()
            ? workspaceService.listWorkspaces()
            : workspaceService.getWorkspace(workspaceId).map(List::of).orElseThrow(() -> new WorkspaceService.WorkspaceNotFoundException(workspaceId));

        int teamCount = 0;
        List<BusinessRunRecord> runs = new ArrayList<>();
        List<BusinessApprovalRecord> pendingApprovals = new ArrayList<>();
        for (WorkspaceRecord workspace : workspaces) {
            teamCount += teamBlueprintService.listTeamBlueprints(workspace.getWorkspaceId()).size();
            runs.addAll(runService.listRuns(workspace.getWorkspaceId(), null, scenarioId, null, 0));
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

        summary.setInsights(generateInsights(summary, runs, pendingApprovals, now));
        summary.setNextActions(generateNextActions(summary));
        return summary;
    }

    private List<BusinessInsightRecord> generateInsights(BusinessInsightSummary summary, List<BusinessRunRecord> runs, List<BusinessApprovalRecord> pendingApprovals, Instant now) {
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

        // Run-based pattern analysis
        if (!runs.isEmpty()) {
            insights.addAll(analyzeRunPatterns(runs, summary.getWorkspaceId(), now));
        }

        // Approval pattern analysis
        if (!pendingApprovals.isEmpty()) {
            insights.addAll(analyzeApprovalPatterns(pendingApprovals, summary.getWorkspaceId(), now));
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

    /**
     * Analyze run records for patterns: failure clustering, scenario performance, version comparison.
     */
    private List<BusinessInsightRecord> analyzeRunPatterns(List<BusinessRunRecord> runs, String workspaceId, Instant now) {
        List<BusinessInsightRecord> insights = new ArrayList<>();

        // 1. Failure mode clustering
        List<BusinessRunRecord> failedRuns = runs.stream()
            .filter(r -> BusinessRunService.FAILED.equals(r.getStatus()))
            .toList();
        if (failedRuns.size() >= 2) {
            Map<String, Long> failureReasons = failedRuns.stream()
                .map(r -> r.getConclusionReason() != null ? r.getConclusionReason() : "unknown")
                .collect(Collectors.groupingBy(s -> s, Collectors.counting()));
            failureReasons.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(2)
                .forEach(entry -> {
                    String reason = entry.getKey();
                    long count = entry.getValue();
                    String insightId = "insight-failure-cluster-" + reason.hashCode();
                    insights.add(insight(insightId, workspaceId,
                        "失败模式聚类：" + reason,
                        "发现 " + count + " 条运行因「" + reason + "」失败，占总失败的 " + Math.round(count * 100.0 / failedRuns.size()) + "% 。",
                        "该失败模式重复出现，说明存在系统性问题（知识缺口、工具异常或规则冲突）。",
                        "针对「" + reason + "」复盘运行步骤，补充相关知识或调整工具配置，并生成团队蓝图优化草案。",
                        "消除重复失败模式，提升自动完成率。",
                        "generate-evolution-proposal", "HIGH",
                        Map.of("failureReason", reason, "count", count, "totalFailed", failedRuns.size()), now));
                });
        }

        // 2. Scenario performance ranking
        Map<String, List<BusinessRunRecord>> runsByScenario = runs.stream()
            .filter(r -> r.getScenario() != null)
            .collect(Collectors.groupingBy(BusinessRunRecord::getScenario));
        runsByScenario.forEach((scenario, scenarioRuns) -> {
            if (scenarioRuns.size() >= 3) {
                int failed = (int) scenarioRuns.stream().filter(r -> BusinessRunService.FAILED.equals(r.getStatus())).count();
                double rate = Math.round(failed * 1000.0 / scenarioRuns.size()) / 10.0;
                if (rate >= 30.0) {
                    insights.add(insight("insight-scenario-weak-" + scenario.hashCode(), workspaceId,
                        "场景「" + scenario + "」失败率偏高",
                        "场景「" + scenario + "」共 " + scenarioRuns.size() + " 次运行，失败 " + failed + " 次（" + rate + "%）。",
                        "该场景的规则、工具或团队配置可能不适合当前任务分布。",
                        "检查场景绑定的团队蓝图和审批规则，考虑生成针对该场景的优化草案。",
                        "降低该场景的失败率，提升整体业务处理效率。",
                        "review-scenario-config", "MEDIUM",
                        Map.of("scenario", scenario, "runCount", scenarioRuns.size(), "failureRate", rate), now));
                }
            }
        });

        // 3. Version performance comparison (if runs have different team versions)
        Map<String, List<BusinessRunRecord>> runsByVersion = runs.stream()
            .filter(r -> r.getTeamVersion() != null)
            .collect(Collectors.groupingBy(BusinessRunRecord::getTeamVersion));
        if (runsByVersion.size() >= 2) {
            Map<String, Double> versionFailureRates = new LinkedHashMap<>();
            runsByVersion.forEach((version, versionRuns) -> {
                int failed = (int) versionRuns.stream().filter(r -> BusinessRunService.FAILED.equals(r.getStatus())).count();
                versionFailureRates.put(version, versionRuns.isEmpty() ? 0.0 : failed * 100.0 / versionRuns.size());
            });
            // Find the worst performing version
            versionFailureRates.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .ifPresent(worst -> {
                    if (worst.getValue() >= 20.0) {
                        insights.add(insight("insight-version-regression", workspaceId,
                            "团队蓝图版本 v" + worst.getKey() + " 失败率偏高",
                            "版本 v" + worst.getKey() + " 的失败率为 " + String.format("%.1f", worst.getValue()) + "% 。",
                            "该版本引入的配置变更可能导致稳定性下降。",
                            "对比 v" + worst.getKey() + " 与上一版本的运行差异，生成回滚或优化提案。",
                            "避免低质量版本影响业务处理。",
                            "compare-versions", "MEDIUM",
                            Map.of("worstVersion", worst.getKey(), "failureRate", worst.getValue()), now));
                    }
                });
        }

        // 4. Recent trend (last 7 days vs previous)
        Instant sevenDaysAgo = now.minus(7, ChronoUnit.DAYS);
        List<BusinessRunRecord> recentRuns = runs.stream().filter(r -> r.getCreatedAt() != null && r.getCreatedAt().isAfter(sevenDaysAgo)).toList();
        List<BusinessRunRecord> olderRuns = runs.stream().filter(r -> r.getCreatedAt() != null && !r.getCreatedAt().isAfter(sevenDaysAgo)).toList();
        if (!recentRuns.isEmpty() && !olderRuns.isEmpty()) {
            double recentFailRate = recentRuns.stream().filter(r -> BusinessRunService.FAILED.equals(r.getStatus())).count() * 100.0 / recentRuns.size();
            double olderFailRate = olderRuns.stream().filter(r -> BusinessRunService.FAILED.equals(r.getStatus())).count() * 100.0 / olderRuns.size();
            double delta = Math.round((recentFailRate - olderFailRate) * 10.0) / 10.0;
            if (delta >= 10.0) {
                insights.add(insight("insight-trend-degrading", workspaceId,
                    "最近 7 天失败率上升 " + delta + "%",
                    "近期失败率 " + String.format("%.1f", recentFailRate) + "%，此前为 " + String.format("%.1f", olderFailRate) + "% 。",
                    "可能由于新场景上线、团队版本变更或外部系统异常。",
                    "排查最近发布的团队蓝图版本和场景配置，必要时回滚。",
                    "及时止损，恢复业务稳定性。",
                    "rollback-check", "HIGH",
                    Map.of("recentFailureRate", recentFailRate, "olderFailureRate", olderFailRate, "delta", delta), now));
            } else if (delta <= -10.0) {
                insights.add(insight("insight-trend-improving", workspaceId,
                    "最近 7 天失败率下降 " + Math.abs(delta) + "%",
                    "近期失败率 " + String.format("%.1f", recentFailRate) + "%，此前为 " + String.format("%.1f", olderFailRate) + "% 。",
                    "最近的优化措施可能已经生效。",
                    "总结有效的优化经验，并考虑推广到其他场景。",
                    "巩固改进成果，持续提升自动化水平。",
                    "collect-best-practices", "INFO",
                    Map.of("recentFailureRate", recentFailRate, "olderFailureRate", olderFailRate, "delta", delta), now));
            }
        }

        return insights;
    }

    /**
     * Analyze approval patterns: auto-triggered vs manual, rule hotspot.
     */
    private List<BusinessInsightRecord> analyzeApprovalPatterns(List<BusinessApprovalRecord> approvals, String workspaceId, Instant now) {
        List<BusinessInsightRecord> insights = new ArrayList<>();

        // Auto-triggered approval hotspot
        Map<String, Long> autoRules = approvals.stream()
            .filter(a -> a.getMetadata() != null && "auto".equals(a.getMetadata().get("source")))
            .map(a -> a.getMetadata().get("trigger") != null ? a.getMetadata().get("trigger").toString() : "unknown")
            .collect(Collectors.groupingBy(s -> s, Collectors.counting()));
        autoRules.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .limit(1)
            .filter(e -> e.getValue() >= 2)
            .forEach(entry -> {
                insights.add(insight("insight-approval-hotspot", workspaceId,
                    "审批热点：「" + entry.getKey() + "」频繁触发",
                    "自动审批规则「" + entry.getKey() + "」已触发 " + entry.getValue() + " 次。",
                    "该规则可能过于敏感，导致大量正常任务被阻塞。",
                    "评估是否需要放宽该规则，或调整规则阈值。",
                    "减少不必要的审批阻塞，提升处理效率。",
                    "adjust-approval-rules", "MEDIUM",
                    Map.of("hotspotRule", entry.getKey(), "triggerCount", entry.getValue()), now));
            });

        return insights;
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
