package com.nousresearch.hermes.scenario;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.nousresearch.hermes.workspace.WorkspaceDashboardIntegration;
import com.nousresearch.hermes.workspace.WorkspaceService;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Business scenario routes. */
public final class ScenarioDashboardIntegration {
    private static final Logger logger = LoggerFactory.getLogger(ScenarioDashboardIntegration.class);

    private ScenarioDashboardIntegration() {
    }

    public static void registerRoutes(Javalin app, ScenarioService service) {
        logger.info("Registering Business Scenario routes");
        app.get("/api/v1/workspaces/{workspaceId}/scenarios", ctx -> listScenarios(ctx, service));
        app.post("/api/v1/workspaces/{workspaceId}/scenarios", ctx -> createScenario(ctx, service));
        app.get("/api/v1/workspaces/{workspaceId}/scenarios/{scenarioId}", ctx -> getScenario(ctx, service));
    }

    static void listScenarios(Context ctx, ScenarioService service) {
        String workspaceId = ctx.pathParam("workspaceId");
        try {
            List<ScenarioRecord> scenarios = service.listScenarios(workspaceId);
            ctx.status(200).json(Map.of("ok", true, "workspaceId", workspaceId, "scenarios", scenarios, "total", scenarios.size()));
        } catch (WorkspaceService.WorkspaceNotFoundException e) {
            ctx.status(404).json(Map.of("ok", false, "error", e.getMessage(), "workspaceId", workspaceId));
        }
    }

    static void createScenario(Context ctx, ScenarioService service) {
        String workspaceId = ctx.pathParam("workspaceId");
        JSONObject body = parseBody(ctx);
        String scenarioId = body.getString("scenarioId");
        if (scenarioId == null || scenarioId.isBlank()) {
            scenarioId = body.getString("id");
        }
        try {
            ScenarioRecord record = service.createScenario(
                workspaceId,
                scenarioId,
                body.getString("name"),
                body.getString("description"),
                body.getString("entryTeamId"),
                parseStringList(body.getJSONArray("successCriteria")),
                parseStringList(body.getJSONArray("approvalRules")),
                WorkspaceDashboardIntegration.objectMap(body.getJSONObject("metadata"))
            );
            ctx.status(201).json(Map.of("ok", true, "workspaceId", workspaceId, "scenarioId", record.getScenarioId(), "scenario", record, "message", "Scenario created"));
        } catch (WorkspaceService.WorkspaceNotFoundException e) {
            ctx.status(404).json(Map.of("ok", false, "error", e.getMessage(), "workspaceId", workspaceId, "scenarioId", scenarioId == null ? "" : scenarioId));
        } catch (ScenarioService.ScenarioAlreadyExistsException e) {
            ctx.status(409).json(Map.of("ok", false, "error", e.getMessage(), "workspaceId", workspaceId, "scenarioId", scenarioId == null ? "" : scenarioId));
        } catch (IllegalArgumentException e) {
            ctx.status(400).json(Map.of("ok", false, "error", e.getMessage(), "workspaceId", workspaceId, "scenarioId", scenarioId == null ? "" : scenarioId));
        } catch (Exception e) {
            logger.error("Failed to create scenario", e);
            ctx.status(500).json(Map.of("ok", false, "error", e.getMessage(), "workspaceId", workspaceId, "scenarioId", scenarioId == null ? "" : scenarioId));
        }
    }

    static void getScenario(Context ctx, ScenarioService service) {
        String workspaceId = ctx.pathParam("workspaceId");
        String scenarioId = ctx.pathParam("scenarioId");
        try {
            service.getScenario(workspaceId, scenarioId)
                .ifPresentOrElse(
                    record -> ctx.status(200).json(Map.of("ok", true, "workspaceId", workspaceId, "scenarioId", scenarioId, "scenario", record)),
                    () -> ctx.status(404).json(Map.of("ok", false, "error", "Scenario not found", "workspaceId", workspaceId, "scenarioId", scenarioId))
                );
        } catch (Exception e) {
            ctx.status(500).json(Map.of("ok", false, "error", e.getMessage(), "workspaceId", workspaceId, "scenarioId", scenarioId));
        }
    }

    static JSONObject parseBody(Context ctx) {
        String body = ctx.body();
        if (body == null || body.isBlank()) {
            return new JSONObject();
        }
        return JSON.parseObject(body);
    }

    static List<String> parseStringList(JSONArray array) {
        if (array == null) {
            return new ArrayList<>();
        }
        List<String> values = new ArrayList<>();
        for (Object item : array) {
            if (item != null) {
                values.add(String.valueOf(item));
            }
        }
        return values;
    }
}
