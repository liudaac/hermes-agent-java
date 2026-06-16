package com.nousresearch.hermes.business.insight;

import com.nousresearch.hermes.workspace.WorkspaceService;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/** Business Portal insights routes. */
public final class BusinessInsightDashboardIntegration {
    private static final Logger logger = LoggerFactory.getLogger(BusinessInsightDashboardIntegration.class);

    private BusinessInsightDashboardIntegration() {
    }

    public static void registerRoutes(Javalin app, BusinessInsightService service) {
        logger.info("Registering Business Insight routes");
        app.get("/api/v1/business/insights", ctx -> insights(ctx, service));
        app.get("/api/v1/workspaces/{workspaceId}/insights", ctx -> workspaceInsights(ctx, service));
    }

    static void insights(Context ctx, BusinessInsightService service) {
        try {
            BusinessInsightSummary summary = service.summarize(ctx.queryParam("workspaceId"), ctx.queryParam("scenarioId"));
            ctx.status(200).json(Map.of(
                "ok", true,
                "entry", "insights",
                "summary", summary,
                "metrics", summary.metricsMap(),
                "insights", summary.getInsights(),
                "total", summary.getInsights().size(),
                "nextActions", summary.getNextActions(),
                "emptyState", summary.getInsights().isEmpty() ? "至少运行 20 条任务后，系统会生成趋势分析和优化建议。" : ""
            ));
        } catch (WorkspaceService.WorkspaceNotFoundException e) {
            ctx.status(404).json(Map.of("ok", false, "error", e.getMessage(), "workspaceId", ctx.queryParam("workspaceId")));
        }
    }

    static void workspaceInsights(Context ctx, BusinessInsightService service) {
        String workspaceId = ctx.pathParam("workspaceId");
        try {
            BusinessInsightSummary summary = service.summarize(workspaceId, ctx.queryParam("scenarioId"));
            ctx.status(200).json(Map.of(
                "ok", true,
                "workspaceId", workspaceId,
                "summary", summary,
                "metrics", summary.metricsMap(),
                "insights", summary.getInsights(),
                "total", summary.getInsights().size(),
                "nextActions", summary.getNextActions()
            ));
        } catch (WorkspaceService.WorkspaceNotFoundException e) {
            ctx.status(404).json(Map.of("ok", false, "error", e.getMessage(), "workspaceId", workspaceId));
        }
    }
}
