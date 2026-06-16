package com.nousresearch.hermes.workspace;

import com.nousresearch.hermes.blueprint.TeamBlueprintRecord;
import com.nousresearch.hermes.business.approval.BusinessApprovalService;
import com.nousresearch.hermes.business.run.BusinessRunService;
import com.nousresearch.hermes.blueprint.TeamBlueprintService;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Business Portal shell APIs for the five business-facing entries:
 * home, teams, runs, approvals and insights.
 */
public final class BusinessPortalDashboardIntegration {
    private static final Logger logger = LoggerFactory.getLogger(BusinessPortalDashboardIntegration.class);

    private BusinessPortalDashboardIntegration() {
    }

    public static void registerRoutes(Javalin app, WorkspaceService workspaceService, TeamBlueprintService teamBlueprintService, BusinessApprovalService approvalService, BusinessRunService runService) {
        logger.info("Registering Business Portal shell routes");
        app.get("/api/v1/business/home", ctx -> home(ctx, workspaceService, teamBlueprintService));
        app.get("/api/v1/business/teams", ctx -> teams(ctx, workspaceService, teamBlueprintService));
        app.get("/api/v1/business/runs", ctx -> runs(ctx, runService));
        app.get("/api/v1/business/approvals", ctx -> approvals(ctx, approvalService));
    }

    static void home(Context ctx, WorkspaceService workspaceService, TeamBlueprintService teamBlueprintService) {
        List<WorkspaceRecord> workspaces = workspaceService.listWorkspaces();
        int teamCount = 0;
        for (WorkspaceRecord workspace : workspaces) {
            teamCount += teamBlueprintService.listTeamBlueprints(workspace.getWorkspaceId()).size();
        }

        Map<String, Object> today = new LinkedHashMap<>();
        today.put("processedTasks", 0);
        today.put("autoCompletionRate", 0);
        today.put("manualInterventions", 0);
        today.put("riskBlocks", 0);
        today.put("averageHandlingSeconds", 0);

        List<Map<String, Object>> nextActions = new ArrayList<>();
        if (workspaces.isEmpty()) {
            nextActions.add(action("create-workspace", "创建第一个业务空间", "从一个部门或项目开始搭建业务智能体团队"));
        } else if (teamCount == 0) {
            nextActions.add(action("create-team", "创建第一个智能体团队", "选择业务场景并确认岗位草案"));
        } else {
            nextActions.add(action("run-sample", "用样例任务试运行", "验证团队解释是否能被业务人员看懂"));
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", true);
        response.put("entry", "home");
        response.put("summary", Map.of(
            "workspaceCount", workspaces.size(),
            "teamCount", teamCount,
            "pendingApprovals", 0,
            "openInsights", 0
        ));
        response.put("today", today);
        response.put("nextActions", nextActions);
        response.put("emptyState", workspaces.isEmpty() ? "还没有业务空间，请先创建一个业务空间。" : null);
        ctx.status(200).json(response);
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
        int limit = parseInt(ctx.queryParam("limit"), 50);
        var runs = runService.listRuns(workspaceId, teamId, status, limit);
        ctx.status(200).json(Map.of(
            "ok", true,
            "entry", "runs",
            "workspaceId", workspaceId == null ? "" : workspaceId,
            "teamId", teamId == null ? "" : teamId,
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
