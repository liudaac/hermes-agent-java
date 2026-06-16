package com.nousresearch.hermes.workspace;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.nousresearch.hermes.blueprint.TeamBlueprintDashboardIntegration;
import com.nousresearch.hermes.blueprint.TeamBlueprintService;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Business Portal workspace routes. */
public final class WorkspaceDashboardIntegration {
    private static final Logger logger = LoggerFactory.getLogger(WorkspaceDashboardIntegration.class);

    private WorkspaceDashboardIntegration() {
    }

    public static void registerRoutes(Javalin app, WorkspaceService workspaceService, TeamBlueprintService teamBlueprintService) {
        logger.info("Registering business workspace routes");
        app.get("/api/v1/workspaces", ctx -> listWorkspaces(ctx, workspaceService));
        app.post("/api/v1/workspaces", ctx -> createWorkspace(ctx, workspaceService));
        app.get("/api/v1/workspaces/{workspaceId}", ctx -> getWorkspace(ctx, workspaceService));
        TeamBlueprintDashboardIntegration.registerRoutes(app, teamBlueprintService);
    }

    static void listWorkspaces(Context ctx, WorkspaceService workspaceService) {
        List<WorkspaceRecord> workspaces = workspaceService.listWorkspaces();
        ctx.status(200).json(Map.of("ok", true, "workspaces", workspaces, "total", workspaces.size()));
    }

    static void createWorkspace(Context ctx, WorkspaceService workspaceService) {
        JSONObject body = parseBody(ctx);
        String workspaceId = body.getString("workspaceId");
        if (workspaceId == null || workspaceId.isBlank()) {
            workspaceId = body.getString("id");
        }
        try {
            WorkspaceRecord record = workspaceService.createWorkspace(
                workspaceId,
                body.getString("name"),
                body.getString("description"),
                body.getString("owner"),
                objectMap(body.getJSONObject("metadata"))
            );
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("ok", true);
            response.put("workspaceId", record.getWorkspaceId());
            response.put("tenantId", record.getTenantId());
            response.put("workspace", record);
            response.put("message", "Workspace created");
            ctx.status(201).json(response);
        } catch (WorkspaceService.WorkspaceAlreadyExistsException e) {
            ctx.status(409).json(Map.of("ok", false, "error", e.getMessage(), "workspaceId", workspaceId));
        } catch (IllegalArgumentException e) {
            ctx.status(400).json(Map.of("ok", false, "error", e.getMessage(), "workspaceId", workspaceId == null ? "" : workspaceId));
        } catch (Exception e) {
            logger.error("Failed to create workspace", e);
            ctx.status(500).json(Map.of("ok", false, "error", e.getMessage(), "workspaceId", workspaceId == null ? "" : workspaceId));
        }
    }

    static void getWorkspace(Context ctx, WorkspaceService workspaceService) {
        String workspaceId = ctx.pathParam("workspaceId");
        workspaceService.getWorkspace(workspaceId)
            .ifPresentOrElse(
                record -> ctx.status(200).json(Map.of("ok", true, "workspace", record)),
                () -> ctx.status(404).json(Map.of("ok", false, "error", "Workspace not found", "workspaceId", workspaceId))
            );
    }

    static JSONObject parseBody(Context ctx) {
        String body = ctx.body();
        if (body == null || body.isBlank()) {
            return new JSONObject();
        }
        return JSON.parseObject(body);
    }

    public static Map<String, Object> objectMap(JSONObject object) {
        if (object == null) {
            return new LinkedHashMap<>();
        }
        return new LinkedHashMap<>(object);
    }
}
