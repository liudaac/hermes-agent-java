package com.nousresearch.hermes.business.template;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Cross-template risk policy aggregation.
 * Surfaces a tenant-wide red/yellow/green view derived from every agent
 * template's {@code risk_policy} block. Returns counts + top samples so the
 * Risk Policy Panel can show "what high-risk actions look like" without
 * having to fetch every template detail.
 */
public final class BusinessRiskPolicyDashboardIntegration {
    private static final Logger logger = LoggerFactory.getLogger(BusinessRiskPolicyDashboardIntegration.class);

    private BusinessRiskPolicyDashboardIntegration() {}

    public static void registerRoutes(Javalin app, BusinessTemplateService templateService) {
        logger.info("Registering Business Risk Policy routes");
        app.get("/api/v1/business/risk-policy/summary", ctx -> summary(ctx, templateService));
        app.get("/api/v1/business/risk-policy/agents/{templateId}", ctx -> byAgent(ctx, templateService));
    }

    static void summary(Context ctx, BusinessTemplateService templateService) {
        String category = ctx.queryParam("category");
        List<AgentTemplate> agents = templateService.listAgents(category);

        int high = 0, medium = 0, low = 0;
        Map<String, Map<String, Object>> byCategory = new TreeMap<>();
        List<Map<String, Object>> topHighRiskActions = new ArrayList<>();

        for (AgentTemplate at : agents) {
            AgentTemplate.RiskPolicy rp = at.getRiskPolicy();
            int hi = rp.getHigh().size();
            int mi = rp.getMedium().size();
            int lo = rp.getLow().size();
            high += hi; medium += mi; low += lo;

            String cat = at.getCategory() != null ? at.getCategory() : "general";
            Map<String, Object> bucket = byCategory.computeIfAbsent(cat, c -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("category", c);
                m.put("agents", 0);
                m.put("high", 0);
                m.put("medium", 0);
                m.put("low", 0);
                return m;
            });
            bucket.put("agents", ((int) bucket.get("agents")) + 1);
            bucket.put("high", ((int) bucket.get("high")) + hi);
            bucket.put("medium", ((int) bucket.get("medium")) + mi);
            bucket.put("low", ((int) bucket.get("low")) + lo);

            for (String item : rp.getHigh()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("templateId", at.getTemplateId());
                entry.put("agentName", at.getName());
                entry.put("category", at.getCategory());
                entry.put("action", item);
                topHighRiskActions.add(entry);
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", true);
        response.put("totals", Map.of(
            "agents", agents.size(),
            "high", high,
            "medium", medium,
            "low", low,
            "coverage", high + medium + low > 0 ? "FULL" : "EMPTY"));
        response.put("byCategory", new ArrayList<>(byCategory.values()));
        response.put("highRiskActions", topHighRiskActions);
        ctx.status(200).json(response);
    }

    static void byAgent(Context ctx, BusinessTemplateService templateService) {
        String templateId = ctx.pathParam("templateId");
        templateService.getAgent(templateId).ifPresentOrElse(
            t -> ctx.status(200).json(Map.of(
                "ok", true,
                "templateId", t.getTemplateId(),
                "name", t.getName(),
                "category", t.getCategory(),
                "riskPolicy", t.getRiskPolicy().toMap())),
            () -> ctx.status(404).json(Map.of("ok", false, "error", "Agent template not found")));
    }

    @SuppressWarnings("unused")
    static JSONObject parseBody(Context ctx) {
        String body = ctx.body();
        if (body == null || body.isBlank()) return new JSONObject();
        return JSON.parseObject(body);
    }

    @SuppressWarnings("unused")
    static String toLower(String v) {
        return v == null ? null : v.toLowerCase(Locale.ROOT);
    }
}
