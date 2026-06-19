package com.nousresearch.hermes.business.run;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.nousresearch.hermes.workspace.WorkspaceDashboardIntegration;
import com.nousresearch.hermes.workspace.WorkspaceService;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.sse.SseClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Business Portal run story routes. */
public final class BusinessRunDashboardIntegration {
    private static final Logger logger = LoggerFactory.getLogger(BusinessRunDashboardIntegration.class);

    private BusinessRunDashboardIntegration() {
    }

    public static void registerRoutes(Javalin app, BusinessRunService service) {
        logger.info("Registering Business Run routes");
        app.get("/api/v1/workspaces/{workspaceId}/runs", ctx -> listRuns(ctx, service));
        app.post("/api/v1/workspaces/{workspaceId}/runs", ctx -> createRun(ctx, service));
        app.get("/api/v1/workspaces/{workspaceId}/runs/{runId}", ctx -> getRun(ctx, service));
        app.sse("/api/v1/workspaces/{workspaceId}/runs/{runId}/stream", client -> streamRun(service, client));
    }

    static void listRuns(Context ctx, BusinessRunService service) {
        String workspaceId = ctx.pathParam("workspaceId");
        try {
            var runs = service.listRuns(workspaceId, ctx.queryParam("teamId"), ctx.queryParam("scenarioId"), ctx.queryParam("status"), parseInt(ctx.queryParam("limit"), 50));
            ctx.status(200).json(Map.of("ok", true, "workspaceId", workspaceId, "runs", runs, "total", runs.size()));
        } catch (WorkspaceService.WorkspaceNotFoundException e) {
            ctx.status(404).json(Map.of("ok", false, "error", e.getMessage(), "workspaceId", workspaceId));
        }
    }

    static void createRun(Context ctx, BusinessRunService service) {
        String workspaceId = ctx.pathParam("workspaceId");
        JSONObject body = parseBody(ctx);
        try {
            BusinessRunRecord record = service.createRun(
                workspaceId,
                body.getString("teamId"),
                body.getString("scenario"),
                body.getString("scenarioId"),
                body.getString("taskTitle"),
                body.getString("taskInput"),
                body.getString("resultSummary"),
                body.getString("conclusionReason"),
                body.getString("systemAction"),
                body.getString("riskJudgement"),
                body.getString("nextSuggestion"),
                body.getString("status"),
                body.getString("technicalTraceRef"),
                parseSteps(body.getJSONArray("steps")),
                body.getLongValue("tokensUsed", 0L),
                body.containsKey("estimatedCost") ? body.getDoubleValue("estimatedCost") : 0.0,
                WorkspaceDashboardIntegration.objectMap(body.getJSONObject("metrics")),
                WorkspaceDashboardIntegration.objectMap(body.getJSONObject("metadata"))
            );
            ctx.status(201).json(Map.of("ok", true, "workspaceId", workspaceId, "runId", record.getRunId(), "run", record, "message", "Business run created"));
        } catch (WorkspaceService.WorkspaceNotFoundException e) {
            ctx.status(404).json(Map.of("ok", false, "error", e.getMessage(), "workspaceId", workspaceId));
        } catch (Exception e) {
            logger.error("Failed to create business run", e);
            ctx.status(500).json(Map.of("ok", false, "error", e.getMessage(), "workspaceId", workspaceId));
        }
    }

    static void getRun(Context ctx, BusinessRunService service) {
        String workspaceId = ctx.pathParam("workspaceId");
        String runId = ctx.pathParam("runId");
        try {
            BusinessRunRecord record = service.requireRun(workspaceId, runId);
            ctx.status(200).json(Map.of("ok", true, "workspaceId", workspaceId, "runId", runId, "run", record));
        } catch (WorkspaceService.WorkspaceNotFoundException | BusinessRunService.BusinessRunNotFoundException e) {
            ctx.status(404).json(Map.of("ok", false, "error", e.getMessage(), "workspaceId", workspaceId, "runId", runId));
        }
    }

    static JSONObject parseBody(Context ctx) {
        String body = ctx.body();
        if (body == null || body.isBlank()) {
            return new JSONObject();
        }
        return JSON.parseObject(body);
    }

    static List<BusinessRunStep> parseSteps(JSONArray array) {
        if (array == null) {
            return new ArrayList<>();
        }
        List<BusinessRunStep> steps = new ArrayList<>();
        for (Object item : array) {
            JSONObject object = item instanceof JSONObject ? (JSONObject) item : JSON.parseObject(JSON.toJSONString(item));
            BusinessRunStep step = new BusinessRunStep()
                .setStepId(object.getString("stepId"))
                .setTitle(object.getString("title"))
                .setSummary(object.getString("summary"))
                .setActor(object.getString("actor"))
                .setEvidence(object.getString("evidence"))
                .setStatus(object.getString("status"))
                .setMetadata(new LinkedHashMap<>(WorkspaceDashboardIntegration.objectMap(object.getJSONObject("metadata"))));
            steps.add(step);
        }
        return steps;
    }

    private static int parseInt(String raw, int fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try { return Integer.parseInt(raw); } catch (Exception ignored) { return fallback; }
    }

    /**
     * SSE endpoint for real-time run progress.
     *
     * <p>Subscribes to the run event bus and pushes events as SSE messages.
     * Sends the current run state immediately, then streams incremental updates.
     * Events: run.started, step.started, step.completed, step.failed, run.completed, run.failed</p>
     */
    static void streamRun(BusinessRunService service, SseClient client) {
        String workspaceId = client.ctx().pathParam("workspaceId");
        String runId = client.ctx().pathParam("runId");

        client.keepAlive();

        try {
            // Send current state immediately
            BusinessRunRecord current = service.requireRun(workspaceId, runId);
            client.sendEvent("run.state", runToJson(current));
        } catch (Exception e) {
            client.sendEvent("error", "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
            client.close();
            return;
        }

        // Subscribe to event bus
        Consumer<RunEventBus.RunEvent> subscriber = event -> {
            try {
                client.sendEvent(event.type().name().toLowerCase().replace('_', '.'), eventToJson(event));
                if (event.type().isTerminal()) {
                    // Give client a moment to receive terminal event, then close
                    try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                    client.close();
                }
            } catch (Exception ex) {
                logger.debug("SSE send failed for run {}: {}", runId, ex.getMessage());
            }
        };

        service.getEventBus().subscribe(runId, subscriber);
        client.onClose(() -> service.getEventBus().unsubscribe(runId, subscriber));
    }

    private static String runToJson(BusinessRunRecord run) {
        JSONObject obj = new JSONObject();
        obj.put("runId", run.getRunId());
        obj.put("workspaceId", run.getWorkspaceId());
        obj.put("teamId", run.getTeamId());
        obj.put("teamVersion", run.getTeamVersion());
        obj.put("scenario", run.getScenario());
        obj.put("scenarioId", run.getScenarioId());
        obj.put("taskTitle", run.getTaskTitle());
        obj.put("status", run.getStatus());
        obj.put("resultSummary", run.getResultSummary());
        obj.put("riskJudgement", run.getRiskJudgement());
        obj.put("steps", run.getSteps().stream().map(BusinessRunStep::toMap).toList());
        obj.put("tokensUsed", run.getTokensUsed());
        obj.put("estimatedCost", run.getEstimatedCost());
        obj.put("createdAt", run.getCreatedAt() != null ? run.getCreatedAt().toString() : null);
        obj.put("updatedAt", run.getUpdatedAt() != null ? run.getUpdatedAt().toString() : null);
        return obj.toJSONString();
    }

    private static String eventToJson(RunEventBus.RunEvent event) {
        JSONObject obj = new JSONObject();
        obj.put("runId", event.runId());
        obj.put("workspaceId", event.workspaceId());
        obj.put("type", event.type().name());
        obj.put("message", event.message());
        obj.put("timestamp", event.timestamp());
        if (event.data() != null && !event.data().isEmpty()) {
            obj.put("data", event.data());
        }
        return obj.toJSONString();
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }
}
