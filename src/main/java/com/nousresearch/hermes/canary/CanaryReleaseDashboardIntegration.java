package com.nousresearch.hermes.canary;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/** Canary release API routes. */
public final class CanaryReleaseDashboardIntegration {
    private static final Logger logger = LoggerFactory.getLogger(CanaryReleaseDashboardIntegration.class);

    private CanaryReleaseDashboardIntegration() {}

    public static void registerRoutes(Javalin app, CanaryReleaseService service) {
        logger.info("Registering Canary Release routes");
        app.get("/api/v1/workspaces/{workspaceId}/teams/{teamId}/canaries", ctx -> listCanaries(ctx, service));
        app.post("/api/v1/workspaces/{workspaceId}/teams/{teamId}/canaries", ctx -> startCanary(ctx, service));
        app.get("/api/v1/workspaces/{workspaceId}/teams/{teamId}/canaries/active", ctx -> getActiveCanary(ctx, service));
        app.post("/api/v1/workspaces/{workspaceId}/teams/{teamId}/canaries/{releaseId}/traffic", ctx -> updateTraffic(ctx, service));
        app.post("/api/v1/workspaces/{workspaceId}/teams/{teamId}/canaries/{releaseId}/promote", ctx -> promoteCanary(ctx, service));
        app.post("/api/v1/workspaces/{workspaceId}/teams/{teamId}/canaries/{releaseId}/rollback", ctx -> rollbackCanary(ctx, service));
    }

    static void listCanaries(Context ctx, CanaryReleaseService service) {
        String workspaceId = ctx.pathParam("workspaceId");
        String teamId = ctx.pathParam("teamId");
        try {
            var releases = service.listCanaries(workspaceId, teamId);
            ctx.status(200).json(Map.of("ok", true, "workspaceId", workspaceId, "teamId", teamId, "canaries", releases));
        } catch (Exception e) {
            ctx.status(500).json(Map.of("ok", false, "error", e.getMessage(), "workspaceId", workspaceId, "teamId", teamId));
        }
    }

    static void startCanary(Context ctx, CanaryReleaseService service) {
        String workspaceId = ctx.pathParam("workspaceId");
        String teamId = ctx.pathParam("teamId");
        JSONObject body = parseBody(ctx);
        try {
            CanaryReleaseRecord record = service.startCanary(
                workspaceId, teamId,
                body.getIntValue("toVersion"),
                body.getIntValue("trafficPercent", 5),
                body.getJSONObject("metadata")
            );
            ctx.status(201).json(Map.of("ok", true, "workspaceId", workspaceId, "teamId", teamId, "canary", record));
        } catch (Exception e) {
            logger.error("Failed to start canary", e);
            ctx.status(500).json(Map.of("ok", false, "error", e.getMessage(), "workspaceId", workspaceId, "teamId", teamId));
        }
    }

    static void getActiveCanary(Context ctx, CanaryReleaseService service) {
        String workspaceId = ctx.pathParam("workspaceId");
        String teamId = ctx.pathParam("teamId");
        try {
            var canary = service.getActiveCanary(workspaceId, teamId);
            canary.ifPresentOrElse(
                record -> ctx.status(200).json(Map.of("ok", true, "workspaceId", workspaceId, "teamId", teamId, "canary", record)),
                () -> ctx.status(200).json(Map.of("ok", true, "workspaceId", workspaceId, "teamId", teamId, "canary", (Object)null))
            );
        } catch (Exception e) {
            ctx.status(500).json(Map.of("ok", false, "error", e.getMessage(), "workspaceId", workspaceId, "teamId", teamId));
        }
    }

    static void updateTraffic(Context ctx, CanaryReleaseService service) {
        String workspaceId = ctx.pathParam("workspaceId");
        String teamId = ctx.pathParam("teamId");
        String releaseId = ctx.pathParam("releaseId");
        JSONObject body = parseBody(ctx);
        try {
            CanaryReleaseRecord record = service.updateTraffic(workspaceId, teamId, releaseId, body.getIntValue("trafficPercent"));
            ctx.status(200).json(Map.of("ok", true, "workspaceId", workspaceId, "teamId", teamId, "canary", record));
        } catch (CanaryReleaseService.CanaryNotFoundException e) {
            ctx.status(404).json(Map.of("ok", false, "error", e.getMessage()));
        } catch (Exception e) {
            ctx.status(500).json(Map.of("ok", false, "error", e.getMessage()));
        }
    }

    static void promoteCanary(Context ctx, CanaryReleaseService service) {
        String workspaceId = ctx.pathParam("workspaceId");
        String teamId = ctx.pathParam("teamId");
        String releaseId = ctx.pathParam("releaseId");
        try {
            CanaryReleaseRecord record = service.promote(workspaceId, teamId, releaseId);
            ctx.status(200).json(Map.of("ok", true, "workspaceId", workspaceId, "teamId", teamId, "canary", record, "message", "Canary promoted to 100%"));
        } catch (CanaryReleaseService.CanaryNotFoundException e) {
            ctx.status(404).json(Map.of("ok", false, "error", e.getMessage()));
        } catch (Exception e) {
            ctx.status(500).json(Map.of("ok", false, "error", e.getMessage()));
        }
    }

    static void rollbackCanary(Context ctx, CanaryReleaseService service) {
        String workspaceId = ctx.pathParam("workspaceId");
        String teamId = ctx.pathParam("teamId");
        String releaseId = ctx.pathParam("releaseId");
        try {
            CanaryReleaseRecord record = service.rollback(workspaceId, teamId, releaseId);
            ctx.status(200).json(Map.of("ok", true, "workspaceId", workspaceId, "teamId", teamId, "canary", record, "message", "Canary rolled back"));
        } catch (CanaryReleaseService.CanaryNotFoundException e) {
            ctx.status(404).json(Map.of("ok", false, "error", e.getMessage()));
        } catch (Exception e) {
            ctx.status(500).json(Map.of("ok", false, "error", e.getMessage()));
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
}
