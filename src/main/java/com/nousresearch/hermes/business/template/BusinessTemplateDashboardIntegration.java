package com.nousresearch.hermes.business.template;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Dashboard routes for the Business Portal template catalog.
 * Exposes:
 *  - GET  /api/v1/business/agent-templates                  list (optional ?category=)
 *  - GET  /api/v1/business/agent-templates/{templateId}     detail
 *  - GET  /api/v1/business/scenario-templates               list (optional ?category=)
 *  - GET  /api/v1/business/scenario-templates/{templateId}  detail
 *  - POST /api/v1/business/scenario-templates/{templateId}/clone
 *  - POST /api/v1/business/templates/reload                 hot reload (dev)
 */
public final class BusinessTemplateDashboardIntegration {
    private static final Logger logger = LoggerFactory.getLogger(BusinessTemplateDashboardIntegration.class);

    private BusinessTemplateDashboardIntegration() {}

    public static void registerRoutes(Javalin app,
                                      BusinessTemplateService templateService,
                                      TemplateCloneService cloneService) {
        logger.info("Registering Business Template routes");
        app.get("/api/v1/business/agent-templates", ctx -> listAgents(ctx, templateService));
        app.get("/api/v1/business/agent-templates/{templateId}", ctx -> getAgent(ctx, templateService));
        app.get("/api/v1/business/scenario-templates", ctx -> listScenarios(ctx, templateService));
        app.get("/api/v1/business/scenario-templates/{templateId}", ctx -> getScenario(ctx, templateService));
        app.post("/api/v1/business/scenario-templates/{templateId}/clone",
            ctx -> cloneScenario(ctx, cloneService));
        app.post("/api/v1/business/templates/reload", ctx -> reload(ctx, templateService));

        // ── User templates (M4) ────────────────────────────────────────
        UserTemplateRepository userRepo = new UserTemplateRepository(templateService);
        app.get("/api/v1/business/user-templates", ctx -> listUserTemplates(ctx, userRepo));
        app.post("/api/v1/business/user-templates/agents", ctx -> uploadUserAgent(ctx, userRepo));
        app.post("/api/v1/business/user-templates/scenarios", ctx -> uploadUserScenario(ctx, userRepo));
        app.delete("/api/v1/business/user-templates/{templateId}",
            ctx -> deleteUserTemplate(ctx, userRepo));
    }

    static void listAgents(Context ctx, BusinessTemplateService service) {
        String category = ctx.queryParam("category");
        List<Map<String, Object>> data = service.listAgents(category).stream()
            .map(AgentTemplate::toMap).toList();
        ctx.status(200).json(Map.of(
            "ok", true,
            "count", data.size(),
            "items", data));
    }

    static void getAgent(Context ctx, BusinessTemplateService service) {
        String templateId = ctx.pathParam("templateId");
        service.getAgent(templateId)
            .ifPresentOrElse(
                t -> ctx.status(200).json(Map.of("ok", true, "item", t.toMap())),
                () -> ctx.status(404).json(Map.of("ok", false, "error", "Agent template not found")));
    }

    static void listScenarios(Context ctx, BusinessTemplateService service) {
        String category = ctx.queryParam("category");
        List<Map<String, Object>> data = service.listScenarios(category).stream()
            .map(ScenarioTemplate::toMap).toList();
        ctx.status(200).json(Map.of(
            "ok", true,
            "count", data.size(),
            "items", data));
    }

    static void getScenario(Context ctx, BusinessTemplateService service) {
        String templateId = ctx.pathParam("templateId");
        service.getScenario(templateId)
            .ifPresentOrElse(
                t -> ctx.status(200).json(Map.of("ok", true, "item", t.toMap())),
                () -> ctx.status(404).json(Map.of("ok", false, "error", "Scenario template not found")));
    }

    static void cloneScenario(Context ctx, TemplateCloneService cloneService) {
        String templateId = ctx.pathParam("templateId");
        JSONObject body = parseBody(ctx);
        CloneRequest req = new CloneRequest();
        req.workspaceId = body.getString("workspaceId");
        req.workspaceName = body.getString("workspaceName");
        req.owner = body.getString("owner");
        try {
            TemplateCloneService.CloneResult result = cloneService.clone(templateId, req);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("ok", true);
            payload.putAll(result.toMap());
            ctx.status(201).json(payload);
        } catch (NoSuchElementException e) {
            ctx.status(404).json(Map.of("ok", false, "error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Failed to clone scenario template {}", templateId, e);
            ctx.status(500).json(Map.of("ok", false, "error", e.getMessage()));
        }
    }

    static void reload(Context ctx, BusinessTemplateService service) {
        try {
            service.reload();
            ctx.status(200).json(Map.of(
                "ok", true,
                "agentCount", service.listAgents().size(),
                "scenarioCount", service.listScenarios().size()));
        } catch (Exception e) {
            logger.error("Failed to reload templates", e);
            ctx.status(500).json(Map.of("ok", false, "error", e.getMessage()));
        }
    }

    static JSONObject parseBody(Context ctx) {
        String body = ctx.body();
        if (body == null || body.isBlank()) return new JSONObject();
        return JSON.parseObject(body);
    }

    // ─── User template handlers ──────────────────────────────────────────

    static void listUserTemplates(Context ctx, UserTemplateRepository repo) {
        ctx.status(200).json(Map.of(
            "ok", true,
            "root", repo.getRoot().toString(),
            "items", repo.listUserTemplates()));
    }

    static void uploadUserAgent(Context ctx, UserTemplateRepository repo) {
        JSONObject body = parseBody(ctx);
        String yamlBody = body.getString("yaml");
        String author = body.getString("author");
        if (yamlBody == null || yamlBody.isBlank()) {
            ctx.status(400).json(Map.of("ok", false, "error", "Missing yaml body"));
            return;
        }
        try {
            AgentTemplate t = repo.uploadAgent(yamlBody, author);
            ctx.status(201).json(Map.of("ok", true, "templateId", t.getTemplateId(), "template", t.toMap()));
        } catch (IllegalArgumentException e) {
            ctx.status(400).json(Map.of("ok", false, "error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Failed to upload user agent template", e);
            ctx.status(500).json(Map.of("ok", false, "error", e.getMessage()));
        }
    }

    static void uploadUserScenario(Context ctx, UserTemplateRepository repo) {
        JSONObject body = parseBody(ctx);
        String yamlBody = body.getString("yaml");
        String author = body.getString("author");
        if (yamlBody == null || yamlBody.isBlank()) {
            ctx.status(400).json(Map.of("ok", false, "error", "Missing yaml body"));
            return;
        }
        try {
            ScenarioTemplate t = repo.uploadScenario(yamlBody, author);
            ctx.status(201).json(Map.of("ok", true, "templateId", t.getTemplateId(), "template", t.toMap()));
        } catch (IllegalArgumentException e) {
            ctx.status(400).json(Map.of("ok", false, "error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Failed to upload user scenario template", e);
            ctx.status(500).json(Map.of("ok", false, "error", e.getMessage()));
        }
    }

    static void deleteUserTemplate(Context ctx, UserTemplateRepository repo) {
        String templateId = ctx.pathParam("templateId");
        boolean removed = repo.deleteByTemplateId(templateId);
        if (removed) {
            ctx.status(200).json(Map.of("ok", true));
        } else {
            ctx.status(404).json(Map.of("ok", false, "error", "Template not found"));
        }
    }
}
