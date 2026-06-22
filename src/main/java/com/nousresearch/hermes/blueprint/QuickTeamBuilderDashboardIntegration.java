package com.nousresearch.hermes.blueprint;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/** Dashboard routes for the AI-driven quick team builder. */
public final class QuickTeamBuilderDashboardIntegration {
    private static final Logger logger = LoggerFactory.getLogger(QuickTeamBuilderDashboardIntegration.class);

    private QuickTeamBuilderDashboardIntegration() {}

    public static void registerRoutes(Javalin app, QuickTeamBuilderService service, TeamBlueprintService blueprintService) {
        logger.info("Registering Quick Team Builder routes");
        app.post("/api/v1/workspaces/{workspaceId}/teams/quick-draft", ctx -> generateDraft(ctx, service));
        app.post("/api/v1/workspaces/{workspaceId}/teams/quick-refine", ctx -> refineDraft(ctx, service));
        app.post("/api/v1/workspaces/{workspaceId}/teams/quick-publish", ctx -> publishDraft(ctx, service, blueprintService));
    }

    static void generateDraft(Context ctx, QuickTeamBuilderService service) {
        String workspaceId = ctx.pathParam("workspaceId");
        JSONObject body = parseBody(ctx);
        String description = body.getString("description");
        if (description == null || description.isBlank()) {
            ctx.status(400).json(Map.of("ok", false, "error", "description is required"));
            return;
        }
        try {
            QuickTeamBuilderService.DraftResult draft = service.generateDraft(workspaceId, description);
            ctx.status(200).json(Map.of("ok", true, "workspaceId", workspaceId, "draft", draftToMap(draft)));
        } catch (Exception e) {
            logger.error("Failed to generate draft", e);
            ctx.status(500).json(Map.of("ok", false, "error", e.getMessage()));
        }
    }

    static void refineDraft(Context ctx, QuickTeamBuilderService service) {
        String workspaceId = ctx.pathParam("workspaceId");
        JSONObject body = parseBody(ctx);
        String description = body.getString("description");
        String previousDraft = body.getString("previousDraft");
        List<String> answers = body.getJSONArray("answers") != null
            ? body.getJSONArray("answers").toList(String.class)
            : List.of();
        if (description == null || description.isBlank() || previousDraft == null || previousDraft.isBlank()) {
            ctx.status(400).json(Map.of("ok", false, "error", "description and previousDraft are required"));
            return;
        }
        try {
            QuickTeamBuilderService.DraftResult draft = service.refineDraft(workspaceId, description, previousDraft, answers);
            ctx.status(200).json(Map.of("ok", true, "workspaceId", workspaceId, "draft", draftToMap(draft)));
        } catch (Exception e) {
            logger.error("Failed to refine draft", e);
            ctx.status(500).json(Map.of("ok", false, "error", e.getMessage()));
        }
    }

    static void publishDraft(Context ctx, QuickTeamBuilderService service, TeamBlueprintService blueprintService) {
        String workspaceId = ctx.pathParam("workspaceId");
        JSONObject body = parseBody(ctx);
        QuickTeamBuilderService.DraftResult draft = parseDraftFromBody(body);
        if (draft == null) {
            ctx.status(400).json(Map.of("ok", false, "error", "draft is required"));
            return;
        }
        try {
            TeamBlueprintRecord record = service.publishDraft(blueprintService, workspaceId, draft);
            ctx.status(201).json(Map.of("ok", true, "workspaceId", workspaceId, "teamId", record.getTeamId(), "team", record));
        } catch (Exception e) {
            logger.error("Failed to publish draft", e);
            ctx.status(500).json(Map.of("ok", false, "error", e.getMessage()));
        }
    }

    static Map<String, Object> draftToMap(QuickTeamBuilderService.DraftResult draft) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("teamId", draft.teamId);
        m.put("teamName", draft.teamName);
        m.put("description", draft.description);
        m.put("scenario", draft.scenario);
        m.put("scenarioId", draft.scenarioId);
        m.put("operatingManual", draft.operatingManual);
        m.put("approvalThreshold", draft.approvalThreshold);
        m.put("tone", draft.tone);
        m.put("agents", draft.agents != null ? draft.agents.stream().map(a -> {
            Map<String, Object> am = new java.util.LinkedHashMap<>();
            am.put("agentId", a.getAgentId());
            am.put("displayName", a.getDisplayName());
            am.put("responsibility", a.getResponsibility());
            am.put("allowedTools", a.getAllowedTools());
            am.put("approvalRules", a.getApprovalRules());
            am.put("knowledgeRefs", a.getKnowledgeRefs());
            return am;
        }).toList() : List.of());
        m.put("suggestedConnectors", draft.suggestedConnectors != null ? draft.suggestedConnectors : List.of());
        m.put("questions", draft.questions != null ? draft.questions : List.of());
        m.put("rawJson", draft.rawJson);
        return m;
    }

    static QuickTeamBuilderService.DraftResult parseDraftFromBody(JSONObject body) {
        JSONObject d = body.getJSONObject("draft");
        if (d == null) return null;
        QuickTeamBuilderService.DraftResult draft = new QuickTeamBuilderService.DraftResult();
        draft.teamId = d.getString("teamId");
        draft.teamName = d.getString("teamName");
        draft.description = d.getString("description");
        draft.scenario = d.getString("scenario");
        draft.scenarioId = d.getString("scenarioId");
        draft.operatingManual = d.getString("operatingManual");
        draft.approvalThreshold = d.getString("approvalThreshold");
        draft.tone = d.getString("tone");
        // agents, connectors, questions will be re-parsed by service if needed
        draft.rawJson = d.toJSONString();
        return draft;
    }

    static JSONObject parseBody(Context ctx) {
        String body = ctx.body();
        if (body == null || body.isBlank()) {
            return new JSONObject();
        }
        return JSON.parseObject(body);
    }
}
