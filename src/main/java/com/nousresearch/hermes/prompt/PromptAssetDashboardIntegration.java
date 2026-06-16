package com.nousresearch.hermes.prompt;

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

/** Prompt asset routes. */
public final class PromptAssetDashboardIntegration {
    private static final Logger logger = LoggerFactory.getLogger(PromptAssetDashboardIntegration.class);

    private PromptAssetDashboardIntegration() {
    }

    public static void registerRoutes(Javalin app, PromptAssetService service) {
        logger.info("Registering Prompt Asset routes");
        app.get("/api/v1/workspaces/{workspaceId}/prompt-assets", ctx -> listPromptAssets(ctx, service));
        app.post("/api/v1/workspaces/{workspaceId}/prompt-assets", ctx -> createPromptAsset(ctx, service));
        app.get("/api/v1/workspaces/{workspaceId}/prompt-assets/{assetId}", ctx -> getPromptAsset(ctx, service));
        app.post("/api/v1/workspaces/{workspaceId}/prompt-assets/{assetId}/versions", ctx -> createDraftVersion(ctx, service));
        app.post("/api/v1/workspaces/{workspaceId}/prompt-assets/{assetId}/versions/{version}/activate", ctx -> activateVersion(ctx, service));
    }

    static void listPromptAssets(Context ctx, PromptAssetService service) {
        String workspaceId = ctx.pathParam("workspaceId");
        try {
            List<PromptAssetRecord> assets = service.listPromptAssets(workspaceId);
            ctx.status(200).json(Map.of("ok", true, "workspaceId", workspaceId, "promptAssets", assets, "total", assets.size()));
        } catch (WorkspaceService.WorkspaceNotFoundException e) {
            ctx.status(404).json(Map.of("ok", false, "error", e.getMessage(), "workspaceId", workspaceId));
        }
    }

    static void createPromptAsset(Context ctx, PromptAssetService service) {
        String workspaceId = ctx.pathParam("workspaceId");
        JSONObject body = parseBody(ctx);
        String assetId = body.getString("assetId");
        if (assetId == null || assetId.isBlank()) {
            assetId = body.getString("id");
        }
        try {
            PromptAssetRecord record = service.createPromptAsset(
                workspaceId,
                assetId,
                body.getString("name"),
                body.getString("purpose"),
                body.getString("content"),
                parseStringList(body.getJSONArray("tags")),
                WorkspaceDashboardIntegration.objectMap(body.getJSONObject("metadata"))
            );
            ctx.status(201).json(Map.of("ok", true, "workspaceId", workspaceId, "assetId", record.getAssetId(), "promptAsset", record, "message", "Prompt asset created"));
        } catch (WorkspaceService.WorkspaceNotFoundException e) {
            ctx.status(404).json(Map.of("ok", false, "error", e.getMessage(), "workspaceId", workspaceId, "assetId", assetId == null ? "" : assetId));
        } catch (PromptAssetService.PromptAssetAlreadyExistsException e) {
            ctx.status(409).json(Map.of("ok", false, "error", e.getMessage(), "workspaceId", workspaceId, "assetId", assetId == null ? "" : assetId));
        } catch (IllegalArgumentException e) {
            ctx.status(400).json(Map.of("ok", false, "error", e.getMessage(), "workspaceId", workspaceId, "assetId", assetId == null ? "" : assetId));
        } catch (Exception e) {
            logger.error("Failed to create prompt asset", e);
            ctx.status(500).json(Map.of("ok", false, "error", e.getMessage(), "workspaceId", workspaceId, "assetId", assetId == null ? "" : assetId));
        }
    }

    static void getPromptAsset(Context ctx, PromptAssetService service) {
        String workspaceId = ctx.pathParam("workspaceId");
        String assetId = ctx.pathParam("assetId");
        try {
            service.getPromptAsset(workspaceId, assetId)
                .ifPresentOrElse(
                    record -> ctx.status(200).json(Map.of("ok", true, "workspaceId", workspaceId, "assetId", assetId, "promptAsset", record)),
                    () -> ctx.status(404).json(Map.of("ok", false, "error", "Prompt asset not found", "workspaceId", workspaceId, "assetId", assetId))
                );
        } catch (Exception e) {
            ctx.status(500).json(Map.of("ok", false, "error", e.getMessage(), "workspaceId", workspaceId, "assetId", assetId));
        }
    }


    static void createDraftVersion(Context ctx, PromptAssetService service) {
        String workspaceId = ctx.pathParam("workspaceId");
        String assetId = ctx.pathParam("assetId");
        JSONObject body = parseBody(ctx);
        try {
            PromptAssetVersion version = service.createDraftVersion(
                workspaceId,
                assetId,
                body.getString("content"),
                body.getString("changeSummary"),
                WorkspaceDashboardIntegration.objectMap(body.getJSONObject("metadata"))
            );
            ctx.status(201).json(Map.of("ok", true, "workspaceId", workspaceId, "assetId", assetId, "version", version, "message", "Prompt asset draft version created"));
        } catch (WorkspaceService.WorkspaceNotFoundException | PromptAssetService.PromptAssetNotFoundException e) {
            ctx.status(404).json(Map.of("ok", false, "error", e.getMessage(), "workspaceId", workspaceId, "assetId", assetId));
        }
    }

    static void activateVersion(Context ctx, PromptAssetService service) {
        String workspaceId = ctx.pathParam("workspaceId");
        String assetId = ctx.pathParam("assetId");
        int version = Integer.parseInt(ctx.pathParam("version"));
        try {
            PromptAssetRecord record = service.activateVersion(workspaceId, assetId, version);
            ctx.status(200).json(Map.of("ok", true, "workspaceId", workspaceId, "assetId", assetId, "activeVersion", record.getActiveVersion(), "promptAsset", record, "message", "Prompt asset version activated"));
        } catch (WorkspaceService.WorkspaceNotFoundException | PromptAssetService.PromptAssetNotFoundException | PromptAssetService.PromptAssetVersionNotFoundException e) {
            ctx.status(404).json(Map.of("ok", false, "error", e.getMessage(), "workspaceId", workspaceId, "assetId", assetId, "version", version));
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
        if (array == null) return new ArrayList<>();
        List<String> values = new ArrayList<>();
        for (Object item : array) {
            if (item != null) values.add(String.valueOf(item));
        }
        return values;
    }
}
