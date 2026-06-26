package com.nousresearch.hermes.workspace;

import com.nousresearch.hermes.blueprint.TeamBlueprintRecord;
import com.nousresearch.hermes.business.approval.BusinessApprovalService;
import com.nousresearch.hermes.business.run.BusinessRunRecord;
import com.nousresearch.hermes.business.run.BusinessRunService;
import com.nousresearch.hermes.business.insight.BusinessInsightService;
import com.nousresearch.hermes.business.insight.BusinessInsightSummary;
import com.nousresearch.hermes.blueprint.TeamBlueprintService;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Business Portal shell APIs for the five business-facing entries:
 * home, teams, runs, approvals and insights.
 */
public final class BusinessPortalDashboardIntegration {
    private static final Logger logger = LoggerFactory.getLogger(BusinessPortalDashboardIntegration.class);

    private BusinessPortalDashboardIntegration() {
    }

    /** 注册 Dashboard 路由。 */
    public static void registerRoutes(Javalin app, WorkspaceService workspaceService, TeamBlueprintService teamBlueprintService, BusinessApprovalService approvalService, BusinessRunService runService, BusinessInsightService insightService) {
        logger.info("Registering Business Portal shell routes");
        app.get("/api/v1/business/home", ctx -> home(ctx, workspaceService, teamBlueprintService, insightService, runService));
        app.get("/api/v1/business/teams", ctx -> teams(ctx, workspaceService, teamBlueprintService));
        app.get("/api/v1/business/runs", ctx -> runs(ctx, runService));
        app.get("/api/v1/business/approvals", ctx -> approvals(ctx, approvalService));
    }

    static void home(Context ctx, WorkspaceService workspaceService, TeamBlueprintService teamBlueprintService, BusinessInsightService insightService, BusinessRunService runService) {
        String workspaceId = ctx.queryParam("workspaceId");
        BusinessInsightSummary insightSummary = insightService.summarize(workspaceId);
        List<WorkspaceRecord> workspaces = workspaceId == null || workspaceId.isBlank()
            ? workspaceService.listWorkspaces()
            : workspaceService.getWorkspace(workspaceId).map(List::of).orElse(List.of());

        Map<String, Object> today = new LinkedHashMap<>();
        today.put("processedTasks", insightSummary.getRunCount());
        today.put("failedRuns", insightSummary.getFailedRunCount());
        today.put("needsApprovalRuns", insightSummary.getNeedsApprovalRunCount());
        today.put("pendingApprovals", insightSummary.getPendingApprovalCount());
        today.put("highRiskApprovals", insightSummary.getHighRiskApprovalCount());
        today.put("failureRate", insightSummary.getFailureRate());
        today.put("autoCompletionRate", autoCompletionRate(insightSummary));

        List<Map<String, Object>> needsAttention = new ArrayList<>();
        if (insightSummary.getPendingApprovalCount() > 0) {
            needsAttention.add(action("review-approvals", "处理待审批", "当前有 " + insightSummary.getPendingApprovalCount() + " 条待审批事项"));
        }
        if (insightSummary.getHighRiskApprovalCount() > 0) {
            needsAttention.add(action("review-high-risk-approvals", "优先处理高风险审批", "当前有 " + insightSummary.getHighRiskApprovalCount() + " 条高风险审批"));
        }
        if (insightSummary.getFailedRunCount() > 0) {
            needsAttention.add(action("review-failed-runs", "复盘失败运行", "当前有 " + insightSummary.getFailedRunCount() + " 条失败运行"));
        }

        Map<String, Object> risk = new LinkedHashMap<>();
        risk.put("level", riskLevel(insightSummary));
        risk.put("pendingApprovals", insightSummary.getPendingApprovalCount());
        risk.put("highRiskApprovals", insightSummary.getHighRiskApprovalCount());
        risk.put("failedRuns", insightSummary.getFailedRunCount());
        risk.put("failureRate", insightSummary.getFailureRate());

        Map<String, Object> teamStatus = new LinkedHashMap<>();
        teamStatus.put("total", insightSummary.getTeamCount());
        teamStatus.put("normal", Math.max(0, insightSummary.getTeamCount() - insightSummary.getFailedRunCount() - insightSummary.getNeedsApprovalRunCount()));
        teamStatus.put("needsAttention", Math.min(insightSummary.getTeamCount(), insightSummary.getFailedRunCount() + insightSummary.getNeedsApprovalRunCount() + insightSummary.getPendingApprovalCount()));
        teamStatus.put("emptyState", insightSummary.getTeamCount() == 0 ? "还没有智能体团队，请先创建或导入团队蓝图。" : "");

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", true);
        response.put("entry", "home");
        response.put("workspaceId", workspaceId == null ? "" : workspaceId);
        response.put("summary", Map.of(
            "workspaceCount", insightSummary.getWorkspaceCount(),
            "teamCount", insightSummary.getTeamCount(),
            "runCount", insightSummary.getRunCount(),
            "pendingApprovals", insightSummary.getPendingApprovalCount(),
            "openInsights", insightSummary.getInsights().size()
        ));
        response.put("today", today);
        response.put("needsAttention", needsAttention);
        response.put("risk", risk);
        response.put("teamStatus", teamStatus);
        response.put("insights", insightSummary.getInsights());
        response.put("nextActions", insightSummary.getNextActions());
        response.put("workspaces", workspaces);
        response.put("emptyState", insightSummary.getWorkspaceCount() == 0 ? "还没有业务空间，请先创建一个业务空间。" : "");

        // ── 7-day trend + response distribution ─────────────────────────
        List<BusinessRunRecord> recent = runService.listRuns(workspaceId, null, null, null, 500);
        response.put("trends", buildTrends(recent));
        response.put("responseDistribution", buildResponseDistribution(recent));
        response.put("recentRuns", recent.stream().limit(5).map(BusinessPortalDashboardIntegration::summarizeRun).toList());

        ctx.status(200).json(response);
    }

    private static Map<String, Object> buildTrends(List<BusinessRunRecord> runs) {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        TreeMap<LocalDate, int[]> buckets = new TreeMap<>();
        for (int i = 6; i >= 0; i--) {
            buckets.put(today.minusDays(i), new int[]{0, 0, 0}); // total, failed, completed
        }
        for (BusinessRunRecord run : runs) {
            Instant ts = run.getCreatedAt();
            if (ts == null) continue;
            LocalDate day = ts.atZone(zone).toLocalDate();
            int[] cell = buckets.get(day);
            if (cell == null) continue;
            cell[0]++;
            String status = run.getStatus() == null ? "" : run.getStatus().toUpperCase(Locale.ROOT);
            if (status.equals("FAILED") || status.equals("ERROR")) cell[1]++;
            else if (status.equals("COMPLETED") || status.equals("SUCCESS")) cell[2]++;
        }
        List<Map<String, Object>> tasks = new ArrayList<>();
        List<Map<String, Object>> failures = new ArrayList<>();
        List<Map<String, Object>> completions = new ArrayList<>();
        buckets.forEach((day, cell) -> {
            tasks.add(Map.of("date", day.toString(), "value", cell[0]));
            failures.add(Map.of("date", day.toString(), "value", cell[1]));
            completions.add(Map.of("date", day.toString(), "value", cell[2]));
        });
        Map<String, Object> trends = new LinkedHashMap<>();
        trends.put("tasks", tasks);
        trends.put("failures", failures);
        trends.put("completions", completions);
        return trends;
    }

    private static Map<String, Object> buildResponseDistribution(List<BusinessRunRecord> runs) {
        List<Long> durations = new ArrayList<>();
        for (BusinessRunRecord run : runs) {
            Instant created = run.getCreatedAt();
            Instant updated = run.getUpdatedAt();
            if (created == null || updated == null) continue;
            long ms = ChronoUnit.MILLIS.between(created, updated);
            if (ms <= 0 || ms > 24L * 60 * 60 * 1000) continue;
            durations.add(ms);
        }
        durations.sort(Long::compare);

        Map<String, Object> dist = new LinkedHashMap<>();
        dist.put("sampleSize", durations.size());
        if (durations.isEmpty()) {
            dist.put("p50Ms", 0);
            dist.put("p95Ms", 0);
            dist.put("buckets", List.of());
            return dist;
        }
        dist.put("p50Ms", percentile(durations, 0.5));
        dist.put("p95Ms", percentile(durations, 0.95));

        // 24 buckets covering 0..p95*1.2
        long max = Math.max(1000L, (long) (percentile(durations, 0.95) * 1.2));
        int buckets = 24;
        long width = Math.max(1, max / buckets);
        int[] hist = new int[buckets];
        for (long d : durations) {
            int idx = (int) Math.min(buckets - 1, d / width);
            hist[idx]++;
        }
        List<Map<String, Object>> bucketList = new ArrayList<>(buckets);
        for (int i = 0; i < buckets; i++) {
            bucketList.add(Map.of(
                "index", i,
                "fromMs", i * width,
                "toMs", (i + 1) * width,
                "count", hist[i]));
        }
        dist.put("buckets", bucketList);
        return dist;
    }

    private static long percentile(List<Long> sorted, double q) {
        if (sorted.isEmpty()) return 0L;
        int idx = (int) Math.min(sorted.size() - 1, Math.max(0, Math.round(q * (sorted.size() - 1))));
        return sorted.get(idx);
    }

    private static Map<String, Object> summarizeRun(BusinessRunRecord run) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("runId", run.getRunId());
        m.put("workspaceId", run.getWorkspaceId());
        m.put("teamId", run.getTeamId());
        m.put("status", run.getStatus());
        m.put("scenario", run.getScenario());
        m.put("scenarioId", run.getScenarioId());
        m.put("taskTitle", run.getTaskTitle());
        m.put("resultSummary", run.getResultSummary());
        m.put("createdAt", run.getCreatedAt() != null ? run.getCreatedAt().toString() : null);
        m.put("updatedAt", run.getUpdatedAt() != null ? run.getUpdatedAt().toString() : null);
        return m;
    }

    static void teams(Context ctx, WorkspaceService workspaceService, TeamBlueprintService teamBlueprintService) {
        String workspaceId = ctx.queryParam("workspaceId");
        List<Map<String, Object>> teamCards = new ArrayList<>();
        List<WorkspaceRecord> workspaces = workspaceId == null || workspaceId.isBlank()
            ? workspaceService.listWorkspaces()
            : workspaceService.getWorkspace(workspaceId).map(List::of).orElse(List.of());

        for (WorkspaceRecord workspace : workspaces) {
            for (TeamBlueprintRecord team : teamBlueprintService.listTeamBlueprints(workspace.getWorkspaceId())) {
                Map<String, Object> card = new LinkedHashMap<>();
                card.put("workspaceId", workspace.getWorkspaceId());
                card.put("teamId", team.getTeamId());
                card.put("name", team.getName());
                card.put("scenario", team.getScenario());
                card.put("activeVersion", team.getActiveVersion());
                card.put("versionCount", team.getVersions().size());
                card.put("status", "ACTIVE");
                teamCards.add(card);
            }
        }

        ctx.status(200).json(Map.of(
            "ok", true,
            "entry", "teams",
            "workspaceId", workspaceId == null ? "" : workspaceId,
            "teams", teamCards,
            "total", teamCards.size(),
            "emptyState", teamCards.isEmpty() ? "还没有智能体团队，请创建或试运行第一个团队。" : ""
        ));
    }

    static void runs(Context ctx, BusinessRunService runService) {
        String workspaceId = ctx.queryParam("workspaceId");
        String teamId = ctx.queryParam("teamId");
        String status = ctx.queryParam("status");
        String scenarioId = ctx.queryParam("scenarioId");
        int limit = parseInt(ctx.queryParam("limit"), 50);
        var runs = runService.listRuns(workspaceId, teamId, scenarioId, status, limit);
        ctx.status(200).json(Map.of(
            "ok", true,
            "entry", "runs",
            "workspaceId", workspaceId == null ? "" : workspaceId,
            "teamId", teamId == null ? "" : teamId,
            "scenarioId", scenarioId == null ? "" : scenarioId,
            "runs", runs,
            "total", runs.size(),
            "emptyState", runs.isEmpty() ? "还没有运行记录。创建团队后，可以用样例任务试运行。" : "",
            "nextActions", runs.isEmpty() ? List.of(action("run-sample", "用样例任务试运行", "生成第一条业务故事化运行记录")) : List.of()
        ));
    }

    static void approvals(Context ctx, BusinessApprovalService approvalService) {
        String workspaceId = ctx.queryParam("workspaceId");
        String status = ctx.queryParam("status");
        var approvals = approvalService.listApprovals(workspaceId, status == null || status.isBlank() ? BusinessApprovalService.PENDING : status);
        ctx.status(200).json(Map.of(
            "ok", true,
            "entry", "approvals",
            "workspaceId", workspaceId == null ? "" : workspaceId,
            "approvals", approvals,
            "total", approvals.size(),
            "emptyState", approvals.isEmpty() ? "当前没有待审批事项。高风险动作和版本发布会出现在这里。" : ""
        ));
    }

    private static double autoCompletionRate(BusinessInsightSummary summary) {
        if (summary.getRunCount() == 0) return 0.0;
        int blocked = summary.getFailedRunCount() + summary.getNeedsApprovalRunCount();
        int completed = Math.max(0, summary.getRunCount() - blocked);
        return Math.round(completed * 1000.0 / summary.getRunCount()) / 10.0;
    }

    private static String riskLevel(BusinessInsightSummary summary) {
        if (summary.getHighRiskApprovalCount() > 0 || summary.getFailureRate() >= 20.0) return "HIGH";
        if (summary.getPendingApprovalCount() > 0 || summary.getFailedRunCount() > 0 || summary.getFailureRate() >= 5.0) return "MEDIUM";
        return "LOW";
    }

    private static int parseInt(String raw, int fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try { return Integer.parseInt(raw); } catch (Exception ignored) { return fallback; }
    }

    private static Map<String, Object> action(String id, String title, String description) {
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("id", id);
        action.put("title", title);
        action.put("description", description);
        return action;
    }
}
