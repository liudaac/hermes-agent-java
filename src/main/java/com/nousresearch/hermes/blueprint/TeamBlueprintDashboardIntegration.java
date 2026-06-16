package com.nousresearch.hermes.blueprint;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.nousresearch.hermes.workspace.WorkspaceDashboardIntegration;
import com.nousresearch.hermes.workspace.WorkspaceService;
import com.nousresearch.hermes.prompt.PromptAssetService;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Business Portal team blueprint routes. */
public final class TeamBlueprintDashboardIntegration {
    private static final Logger logger = LoggerFactory.getLogger(TeamBlueprintDashboardIntegration.class);

    private TeamBlueprintDashboardIntegration() {
    }

    public static void registerRoutes(Javalin app, TeamBlueprintService service) {
        logger.info("Registering business team blueprint routes");
        app.get("/api/v1/workspaces/{workspaceId}/team-blueprints", ctx -> listTeamBlueprints(ctx, service));
        app.post("/api/v1/workspaces/{workspaceId}/team-blueprints", ctx -> createTeamBlueprint(ctx, service));
        app.get("/api/v1/workspaces/{workspaceId}/team-blueprints/{teamId}", ctx -> getTeamBlueprint(ctx, service));
        app.post("/api/v1/workspaces/{workspaceId}/team-blueprints/{teamId}/versions", ctx -> createDraftVersion(ctx, service));
        app.post("/api/v1/workspaces/{workspaceId}/team-blueprints/{teamId}/versions/{version}/activate", ctx -> activateVersion(ctx, service));
    }

    static void listTeamBlueprints(Context ctx, TeamBlueprintService service) {
        String workspaceId = ctx.pathParam("workspaceId");
        try {
            List<TeamBlueprintRecord> teams = service.listTeamBlueprints(workspaceId);
            ctx.status(200).json(Map.of("ok", true, "workspaceId", workspaceId, "teams", teams, "total", teams.size()));
        } catch (WorkspaceService.WorkspaceNotFoundException e) {
            ctx.status(404).json(Map.of("ok", false, "error", e.getMessage(), "workspaceId", workspaceId));
        }
    }

    static void createTeamBlueprint(Context ctx, TeamBlueprintService service) {
        String workspaceId = ctx.pathParam("workspaceId");
        JSONObject body = parseBody(ctx);
        String teamId = body.getString("teamId");
        if (teamId == null || teamId.isBlank()) {
            teamId = body.getString("id");
        }
        try {
            TeamBlueprintRecord record = service.createTeamBlueprint(
                workspaceId,
                teamId,
                body.getString("name"),
                body.getString("description"),
                body.getString("scenario"),
                body.getString("scenarioId"),
                parseAgents(body.getJSONArray("agents")),
                parseStringList(body.getJSONArray("promptAssetRefs")),
                body.getString("operatingManual"),
                WorkspaceDashboardIntegration.objectMap(body.getJSONObject("metadata"))
            );
            ctx.status(201).json(Map.of("ok", true, "workspaceId", workspaceId, "teamId", record.getTeamId(), "team", record, "message", "Team blueprint created"));
        } catch (WorkspaceService.WorkspaceNotFoundException | TeamBlueprintService.TeamBlueprintNotFoundException | PromptAssetService.PromptAssetNotFoundException e) {
            ctx.status(404).json(Map.of("ok", false, "error", e.getMessage(), "workspaceId", workspaceId, "teamId", teamId == null ? "" : teamId));
        } catch (TeamBlueprintService.TeamBlueprintAlreadyExistsException e) {
            ctx.status(409).json(Map.of("ok", false, "error", e.getMessage(), "workspaceId", workspaceId, "teamId", teamId == null ? "" : teamId));
        } catch (IllegalArgumentException e) {
            ctx.status(400).json(Map.of("ok", false, "error", e.getMessage(), "workspaceId", workspaceId, "teamId", teamId == null ? "" : teamId));
        } catch (Exception e) {
            logger.error("Failed to create team blueprint", e);
            ctx.status(500).json(Map.of("ok", false, "error", e.getMessage(), "workspaceId", workspaceId, "teamId", teamId == null ? "" : teamId));
        }
    }

    static void getTeamBlueprint(Context ctx, TeamBlueprintService service) {
        String workspaceId = ctx.pathParam("workspaceId");
        String teamId = ctx.pathParam("teamId");
        try {
            service.getTeamBlueprint(workspaceId, teamId)
                .ifPresentOrElse(
                    record -> ctx.status(200).json(Map.of("ok", true, "workspaceId", workspaceId, "teamId", teamId, "team", record)),
                    () -> ctx.status(404).json(Map.of("ok", false, "error", "Team blueprint not found", "workspaceId", workspaceId, "teamId", teamId))
                );
        } catch (Exception e) {
            ctx.status(500).json(Map.of("ok", false, "error", e.getMessage(), "workspaceId", workspaceId, "teamId", teamId));
        }
    }

    static void createDraftVersion(Context ctx, TeamBlueprintService service) {
        String workspaceId = ctx.pathParam("workspaceId");
        String teamId = ctx.pathParam("teamId");
        JSONObject body = parseBody(ctx);
        try {
            TeamBlueprintVersion version = service.createDraftVersion(
                workspaceId,
                teamId,
                body.getString("changeSummary"),
                parseAgents(body.getJSONArray("agents")),
                parseStringList(body.getJSONArray("promptAssetRefs")),
                body.getString("operatingManual"),
                WorkspaceDashboardIntegration.objectMap(body.getJSONObject("metadata"))
            );
            ctx.status(201).json(Map.of("ok", true, "workspaceId", workspaceId, "teamId", teamId, "version", version, "message", "Draft version created"));
        } catch (WorkspaceService.WorkspaceNotFoundException | TeamBlueprintService.TeamBlueprintNotFoundException | PromptAssetService.PromptAssetNotFoundException e) {
            ctx.status(404).json(Map.of("ok", false, "error", e.getMessage(), "workspaceId", workspaceId, "teamId", teamId));
        } catch (Exception e) {
            ctx.status(500).json(Map.of("ok", false, "error", e.getMessage(), "workspaceId", workspaceId, "teamId", teamId));
        }
    }

    static void activateVersion(Context ctx, TeamBlueprintService service) {
        String workspaceId = ctx.pathParam("workspaceId");
        String teamId = ctx.pathParam("teamId");
        int version = Integer.parseInt(ctx.pathParam("version"));
        try {
            TeamBlueprintRecord record = service.activateVersion(workspaceId, teamId, version);
            ctx.status(200).json(Map.of("ok", true, "workspaceId", workspaceId, "teamId", teamId, "activeVersion", record.getActiveVersion(), "team", record, "message", "Team blueprint version activated"));
        } catch (WorkspaceService.WorkspaceNotFoundException | TeamBlueprintService.TeamBlueprintNotFoundException | TeamBlueprintService.TeamBlueprintVersionNotFoundException e) {
            ctx.status(404).json(Map.of("ok", false, "error", e.getMessage(), "workspaceId", workspaceId, "teamId", teamId, "version", version));
        } catch (Exception e) {
            ctx.status(500).json(Map.of("ok", false, "error", e.getMessage(), "workspaceId", workspaceId, "teamId", teamId, "version", version));
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

    static List<AgentBlueprintRecord> parseAgents(JSONArray array) {
        if (array == null) {
            return new ArrayList<>();
        }
        List<AgentBlueprintRecord> agents = new ArrayList<>();
        for (Object item : array) {
            JSONObject object = item instanceof JSONObject ? (JSONObject) item : JSON.parseObject(JSON.toJSONString(item));
            AgentBlueprintRecord agent = new AgentBlueprintRecord()
                .setAgentId(object.getString("agentId"))
                .setDisplayName(object.getString("displayName"))
                .setResponsibility(object.getString("responsibility"))
                .setKnowledgeRefs(parseStringList(object.getJSONArray("knowledgeRefs")))
                .setAllowedTools(parseStringList(object.getJSONArray("allowedTools")))
                .setApprovalRules(parseStringList(object.getJSONArray("approvalRules")))
                .setMetadata(new LinkedHashMap<>(WorkspaceDashboardIntegration.objectMap(object.getJSONObject("metadata"))));
            agents.add(agent);
        }
        return agents;
    }
}
