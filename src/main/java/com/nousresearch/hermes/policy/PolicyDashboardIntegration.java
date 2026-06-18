package com.nousresearch.hermes.policy;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/** Workspace policy routes for skill/tool governance. */
public final class PolicyDashboardIntegration {
    private static final Logger logger = LoggerFactory.getLogger(PolicyDashboardIntegration.class);

    private PolicyDashboardIntegration() {}

    public static void registerRoutes(Javalin app, PolicyService service) {
        logger.info("Registering Policy routes");
        app.get("/api/v1/workspaces/{workspaceId}/policy", ctx -> getPolicy(ctx, service));
        app.put("/api/v1/workspaces/{workspaceId}/policy", ctx -> updatePolicy(ctx, service));
        app.get("/api/v1/workspaces/{workspaceId}/teams/{teamId}/agents/{agentId}/allowed-skills", ctx -> allowedSkills(ctx, service));
        app.get("/api/v1/workspaces/{workspaceId}/teams/{teamId}/agents/{agentId}/allowed-tools", ctx -> allowedTools(ctx, service));
        app.post("/api/v1/workspaces/{workspaceId}/policy/check-skill", ctx -> checkSkill(ctx, service));
        app.post("/api/v1/workspaces/{workspaceId}/policy/check-tool", ctx -> checkTool(ctx, service));
    }

    static void getPolicy(Context ctx, PolicyService service) {
        String workspaceId = ctx.pathParam("workspaceId");
        try {
            WorkspacePolicyRecord policy = service.getOrCreatePolicy(workspaceId);
            ctx.status(200).json(Map.of("ok", true, "workspaceId", workspaceId, "policy", policy));
        } catch (Exception e) {
            ctx.status(500).json(Map.of("ok", false, "error", e.getMessage(), "workspaceId", workspaceId));
        }
    }

    static void updatePolicy(Context ctx, PolicyService service) {
        String workspaceId = ctx.pathParam("workspaceId");
        JSONObject body = parseBody(ctx);
        try {
            WorkspacePolicyRecord policy = service.updatePolicy(
                workspaceId,
                toStringList(body.getJSONArray("allowedSkills")),
                toStringList(body.getJSONArray("deniedSkills")),
                toStringList(body.getJSONArray("allowedTools")),
                toStringList(body.getJSONArray("deniedTools"))
            );
            ctx.status(200).json(Map.of("ok", true, "workspaceId", workspaceId, "policy", policy));
        } catch (Exception e) {
            logger.error("Failed to update policy", e);
            ctx.status(500).json(Map.of("ok", false, "error", e.getMessage(), "workspaceId", workspaceId));
        }
    }

    static void allowedSkills(Context ctx, PolicyService service) {
        String workspaceId = ctx.pathParam("workspaceId");
        String teamId = ctx.pathParam("teamId");
        String agentId = ctx.pathParam("agentId");
        try {
            var skills = service.resolveAllowedSkills(workspaceId, teamId, agentId);
            ctx.status(200).json(Map.of("ok", true, "workspaceId", workspaceId, "teamId", teamId, "agentId", agentId, "allowedSkills", skills));
        } catch (Exception e) {
            ctx.status(500).json(Map.of("ok", false, "error", e.getMessage(), "workspaceId", workspaceId));
        }
    }

    static void allowedTools(Context ctx, PolicyService service) {
        String workspaceId = ctx.pathParam("workspaceId");
        String teamId = ctx.pathParam("teamId");
        String agentId = ctx.pathParam("agentId");
        try {
            var tools = service.resolveAllowedTools(workspaceId, teamId, agentId);
            ctx.status(200).json(Map.of("ok", true, "workspaceId", workspaceId, "teamId", teamId, "agentId", agentId, "allowedTools", tools));
        } catch (Exception e) {
            ctx.status(500).json(Map.of("ok", false, "error", e.getMessage(), "workspaceId", workspaceId));
        }
    }

    static void checkSkill(Context ctx, PolicyService service) {
        String workspaceId = ctx.pathParam("workspaceId");
        JSONObject body = parseBody(ctx);
        String teamId = body.getString("teamId");
        String agentId = body.getString("agentId");
        String skillName = body.getString("skillName");
        try {
            boolean permitted = service.isSkillPermitted(workspaceId, teamId, agentId, skillName);
            ctx.status(200).json(Map.of("ok", true, "workspaceId", workspaceId, "permitted", permitted, "skillName", skillName));
        } catch (Exception e) {
            ctx.status(500).json(Map.of("ok", false, "error", e.getMessage(), "workspaceId", workspaceId));
        }
    }

    static void checkTool(Context ctx, PolicyService service) {
        String workspaceId = ctx.pathParam("workspaceId");
        JSONObject body = parseBody(ctx);
        String teamId = body.getString("teamId");
        String agentId = body.getString("agentId");
        String toolName = body.getString("toolName");
        try {
            boolean permitted = service.isToolPermitted(workspaceId, teamId, agentId, toolName);
            ctx.status(200).json(Map.of("ok", true, "workspaceId", workspaceId, "permitted", permitted, "toolName", toolName));
        } catch (Exception e) {
            ctx.status(500).json(Map.of("ok", false, "error", e.getMessage(), "workspaceId", workspaceId));
        }
    }

    private static JSONObject parseBody(Context ctx) {
        try {
            String body = ctx.body();
            return body != null && !body.isBlank() ? JSON.parseObject(body) : new JSONObject();
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> toStringList(Object obj) {
        if (obj == null) return null;
        if (obj instanceof List) {
            return ((List<?>) obj).stream().map(Object::toString).toList();
        }
        return null;
    }
}
