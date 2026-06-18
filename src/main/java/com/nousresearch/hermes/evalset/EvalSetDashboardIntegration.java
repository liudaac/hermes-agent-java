package com.nousresearch.hermes.evalset;

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

/** EvalSet API routes. */
public final class EvalSetDashboardIntegration {
    private static final Logger logger = LoggerFactory.getLogger(EvalSetDashboardIntegration.class);

    private EvalSetDashboardIntegration() {}

    public static void registerRoutes(Javalin app, EvalSetService service) {
        logger.info("Registering EvalSet routes");
        app.get("/api/v1/workspaces/{workspaceId}/scenarios/{scenarioId}/evalsets", ctx -> listEvalSets(ctx, service));
        app.post("/api/v1/workspaces/{workspaceId}/scenarios/{scenarioId}/evalsets", ctx -> createEvalSet(ctx, service));
        app.get("/api/v1/workspaces/{workspaceId}/scenarios/{scenarioId}/evalsets/{evalSetId}", ctx -> getEvalSet(ctx, service));
        app.delete("/api/v1/workspaces/{workspaceId}/scenarios/{scenarioId}/evalsets/{evalSetId}", ctx -> deleteEvalSet(ctx, service));
    }

    static void listEvalSets(Context ctx, EvalSetService service) {
        String workspaceId = ctx.pathParam("workspaceId");
        String scenarioId = ctx.pathParam("scenarioId");
        try {
            var sets = service.listEvalSets(workspaceId, scenarioId);
            ctx.status(200).json(Map.of("ok", true, "workspaceId", workspaceId, "scenarioId", scenarioId, "evalSets", sets, "total", sets.size()));
        } catch (Exception e) {
            ctx.status(500).json(Map.of("ok", false, "error", e.getMessage(), "workspaceId", workspaceId, "scenarioId", scenarioId));
        }
    }

    static void createEvalSet(Context ctx, EvalSetService service) {
        String workspaceId = ctx.pathParam("workspaceId");
        String scenarioId = ctx.pathParam("scenarioId");
        JSONObject body = parseBody(ctx);
        try {
            List<EvalSetRecord.EvalCase> cases = new ArrayList<>();
            JSONArray caseArray = body.getJSONArray("cases");
            if (caseArray != null) {
                for (int i = 0; i < caseArray.size(); i++) {
                    JSONObject c = caseArray.getJSONObject(i);
                    cases.add(new EvalSetRecord.EvalCase()
                        .setCaseId(c.getString("caseId") != null ? c.getString("caseId") : "case-" + (i + 1))
                        .setName(c.getString("name"))
                        .setInput(c.getString("input"))
                        .setExpectedOutput(c.getString("expectedOutput"))
                        .setSuccessCriteria(parseStringList(c.getJSONArray("successCriteria"))));
                }
            }
            EvalSetRecord record = service.createEvalSet(
                workspaceId, scenarioId, body.getString("evalSetId"), body.getString("name"),
                body.getString("description"), cases, body.getJSONObject("metadata")
            );
            ctx.status(201).json(Map.of("ok", true, "workspaceId", workspaceId, "scenarioId", scenarioId, "evalSetId", record.getEvalSetId(), "evalSet", record));
        } catch (Exception e) {
            logger.error("Failed to create eval set", e);
            ctx.status(500).json(Map.of("ok", false, "error", e.getMessage(), "workspaceId", workspaceId, "scenarioId", scenarioId));
        }
    }

    static void getEvalSet(Context ctx, EvalSetService service) {
        String workspaceId = ctx.pathParam("workspaceId");
        String scenarioId = ctx.pathParam("scenarioId");
        String evalSetId = ctx.pathParam("evalSetId");
        try {
            EvalSetRecord record = service.getEvalSet(workspaceId, scenarioId, evalSetId);
            ctx.status(200).json(Map.of("ok", true, "workspaceId", workspaceId, "scenarioId", scenarioId, "evalSetId", evalSetId, "evalSet", record));
        } catch (EvalSetService.EvalSetNotFoundException e) {
            ctx.status(404).json(Map.of("ok", false, "error", e.getMessage(), "workspaceId", workspaceId, "scenarioId", scenarioId, "evalSetId", evalSetId));
        } catch (Exception e) {
            ctx.status(500).json(Map.of("ok", false, "error", e.getMessage(), "workspaceId", workspaceId, "scenarioId", scenarioId, "evalSetId", evalSetId));
        }
    }

    static void deleteEvalSet(Context ctx, EvalSetService service) {
        String workspaceId = ctx.pathParam("workspaceId");
        String scenarioId = ctx.pathParam("scenarioId");
        String evalSetId = ctx.pathParam("evalSetId");
        try {
            service.deleteEvalSet(workspaceId, scenarioId, evalSetId);
            ctx.status(200).json(Map.of("ok", true, "workspaceId", workspaceId, "scenarioId", scenarioId, "evalSetId", evalSetId));
        } catch (Exception e) {
            ctx.status(500).json(Map.of("ok", false, "error", e.getMessage(), "workspaceId", workspaceId, "scenarioId", scenarioId, "evalSetId", evalSetId));
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
        if (obj == null) return List.of();
        if (obj instanceof List) {
            return ((List<?>) obj).stream().map(Object::toString).toList();
        }
        return List.of();
    }
}
