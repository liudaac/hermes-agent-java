package com.nousresearch.hermes.memory;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Active Memory API routes. */
public final class ActiveMemoryDashboardIntegration {
    private static final Logger logger = LoggerFactory.getLogger(ActiveMemoryDashboardIntegration.class);

    private ActiveMemoryDashboardIntegration() {}

    public static void registerRoutes(Javalin app, ActiveMemoryService service) {
        logger.info("Registering Active Memory routes");
        app.get("/api/v1/workspaces/{workspaceId}/memories", ctx -> listMemories(ctx, service));
        app.post("/api/v1/workspaces/{workspaceId}/memories", ctx -> createMemory(ctx, service));
        app.get("/api/v1/workspaces/{workspaceId}/memories/{memoryId}", ctx -> getMemory(ctx, service));
        app.put("/api/v1/workspaces/{workspaceId}/memories/{memoryId}", ctx -> updateMemory(ctx, service));
        app.delete("/api/v1/workspaces/{workspaceId}/memories/{memoryId}", ctx -> deleteMemory(ctx, service));
        app.post("/api/v1/workspaces/{workspaceId}/memories/recall", ctx -> recallMemories(ctx, service));
    }

    static void listMemories(Context ctx, ActiveMemoryService service) {
        String workspaceId = ctx.pathParam("workspaceId");
        try {
            var memories = service.listMemories(workspaceId);
            ctx.status(200).json(Map.of("ok", true, "workspaceId", workspaceId, "memories", memories, "total", memories.size()));
        } catch (Exception e) {
            ctx.status(500).json(Map.of("ok", false, "error", e.getMessage(), "workspaceId", workspaceId));
        }
    }

    static void createMemory(Context ctx, ActiveMemoryService service) {
        String workspaceId = ctx.pathParam("workspaceId");
        JSONObject body = parseBody(ctx);
        try {
            ActiveMemoryRecord record = service.createMemory(
                workspaceId, body.getString("memoryId"), body.getString("type"), body.getString("title"),
                body.getString("content"), parseStringList(body.getJSONArray("tags")),
                parseStringList(body.getJSONArray("scenarioIds")), parseStringList(body.getJSONArray("teamIds")),
                body.getJSONObject("metadata")
            );
            ctx.status(201).json(Map.of("ok", true, "workspaceId", workspaceId, "memoryId", record.getMemoryId(), "memory", record));
        } catch (Exception e) {
            logger.error("Failed to create memory", e);
            ctx.status(500).json(Map.of("ok", false, "error", e.getMessage(), "workspaceId", workspaceId));
        }
    }

    static void getMemory(Context ctx, ActiveMemoryService service) {
        String workspaceId = ctx.pathParam("workspaceId");
        String memoryId = ctx.pathParam("memoryId");
        try {
            ActiveMemoryRecord record = service.getMemory(workspaceId, memoryId);
            ctx.status(200).json(Map.of("ok", true, "workspaceId", workspaceId, "memoryId", memoryId, "memory", record));
        } catch (ActiveMemoryService.MemoryNotFoundException e) {
            ctx.status(404).json(Map.of("ok", false, "error", e.getMessage(), "workspaceId", workspaceId, "memoryId", memoryId));
        } catch (Exception e) {
            ctx.status(500).json(Map.of("ok", false, "error", e.getMessage(), "workspaceId", workspaceId, "memoryId", memoryId));
        }
    }

    static void updateMemory(Context ctx, ActiveMemoryService service) {
        String workspaceId = ctx.pathParam("workspaceId");
        String memoryId = ctx.pathParam("memoryId");
        JSONObject body = parseBody(ctx);
        try {
            ActiveMemoryRecord record = service.updateMemory(
                workspaceId, memoryId, body.getString("title"), body.getString("content"),
                parseStringList(body.getJSONArray("tags")), parseStringList(body.getJSONArray("scenarioIds")),
                parseStringList(body.getJSONArray("teamIds")), body.getJSONObject("metadata")
            );
            ctx.status(200).json(Map.of("ok", true, "workspaceId", workspaceId, "memoryId", memoryId, "memory", record));
        } catch (ActiveMemoryService.MemoryNotFoundException e) {
            ctx.status(404).json(Map.of("ok", false, "error", e.getMessage(), "workspaceId", workspaceId, "memoryId", memoryId));
        } catch (Exception e) {
            ctx.status(500).json(Map.of("ok", false, "error", e.getMessage(), "workspaceId", workspaceId, "memoryId", memoryId));
        }
    }

    static void deleteMemory(Context ctx, ActiveMemoryService service) {
        String workspaceId = ctx.pathParam("workspaceId");
        String memoryId = ctx.pathParam("memoryId");
        try {
            service.deleteMemory(workspaceId, memoryId);
            ctx.status(200).json(Map.of("ok", true, "workspaceId", workspaceId, "memoryId", memoryId));
        } catch (Exception e) {
            ctx.status(500).json(Map.of("ok", false, "error", e.getMessage(), "workspaceId", workspaceId, "memoryId", memoryId));
        }
    }

    static void recallMemories(Context ctx, ActiveMemoryService service) {
        String workspaceId = ctx.pathParam("workspaceId");
        JSONObject body = parseBody(ctx);
        try {
            var memories = service.recall(
                workspaceId, body.getString("scenarioId"), parseStringList(body.getJSONArray("tags")),
                body.getString("query"), body.getIntValue("limit", 10)
            );
            ctx.status(200).json(Map.of("ok", true, "workspaceId", workspaceId, "memories", memories, "count", memories.size()));
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
    private static List<String> parseStringList(Object obj) {
        if (obj == null) return null;
        if (obj instanceof List) {
            return ((List<?>) obj).stream().map(Object::toString).toList();
        }
        return null;
    }
}
