package com.nousresearch.hermes.business.template;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.nousresearch.hermes.business.run.BusinessRunRecord;
import com.nousresearch.hermes.business.run.BusinessRunService;
import com.nousresearch.hermes.business.run.BusinessRunStep;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * M4 Industry Dashboard — slices the run pool by vertical category
 * (HR / 财务 / 固资 / 物流) using the agent template metadata attached when a
 * scenario template was cloned. Returns rollup metrics + leaderboard for the
 * front-end {@code IndustryDashboardPage}.
 */
public final class BusinessIndustryDashboardIntegration {
    private static final Logger logger = LoggerFactory.getLogger(BusinessIndustryDashboardIntegration.class);

    private BusinessIndustryDashboardIntegration() {}

    public static void registerRoutes(Javalin app,
                                      BusinessRunService runService,
                                      BusinessTemplateService templateService) {
        logger.info("Registering Industry Dashboard routes");
        app.get("/api/v1/business/industry-dashboard", ctx -> dashboard(ctx, runService, templateService));
    }

    static void dashboard(Context ctx, BusinessRunService runService, BusinessTemplateService templateService) {
        String category = lower(ctx.queryParam("category"));
        int limit = parseIntOrDefault(ctx.queryParam("limit"), 500);
        List<BusinessRunRecord> all = runService.listRuns(null, null, null, null, limit);

        // Build category breakdown
        Map<String, int[]> byCategory = new TreeMap<>(); // category -> [total, completed, failed]
        Map<String, Long> latencySumByCategory = new TreeMap<>();
        Map<String, Long> latencyCountByCategory = new TreeMap<>();
        Map<String, Long> agentTaskCounts = new TreeMap<>();

        for (BusinessRunRecord run : all) {
            String runCategory = resolveCategory(run, templateService);
            if (category != null && !category.isBlank() && !category.equalsIgnoreCase(runCategory)) continue;

            int[] cell = byCategory.computeIfAbsent(runCategory, k -> new int[]{0, 0, 0});
            cell[0]++;
            String status = run.getStatus() == null ? "" : run.getStatus().toUpperCase(Locale.ROOT);
            if (status.equals("COMPLETED") || status.equals("SUCCESS")) cell[1]++;
            if (status.equals("FAILED") || status.equals("ERROR")) cell[2]++;

            Instant cAt = run.getCreatedAt();
            Instant uAt = run.getUpdatedAt();
            if (cAt != null && uAt != null) {
                long ms = ChronoUnit.MILLIS.between(cAt, uAt);
                if (ms > 0 && ms < 24L * 60 * 60 * 1000) {
                    latencySumByCategory.merge(runCategory, ms, Long::sum);
                    latencyCountByCategory.merge(runCategory, 1L, Long::sum);
                }
            }

            for (BusinessRunStep step : run.getSteps()) {
                String actor = step.getActor() != null ? step.getActor()
                    : step.getAgentId() != null ? step.getAgentId() : "未命名";
                agentTaskCounts.merge(actor, 1L, Long::sum);
            }
        }

        // Daily trend (filtered)
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        TreeMap<LocalDate, int[]> trend = new TreeMap<>();
        for (int i = 6; i >= 0; i--) trend.put(today.minusDays(i), new int[]{0, 0});
        for (BusinessRunRecord run : all) {
            String runCategory = resolveCategory(run, templateService);
            if (category != null && !category.isBlank() && !category.equalsIgnoreCase(runCategory)) continue;
            Instant cAt = run.getCreatedAt();
            if (cAt == null) continue;
            LocalDate day = cAt.atZone(zone).toLocalDate();
            int[] cell = trend.get(day);
            if (cell == null) continue;
            cell[0]++;
            String status = run.getStatus() == null ? "" : run.getStatus().toUpperCase(Locale.ROOT);
            if (status.equals("FAILED") || status.equals("ERROR")) cell[1]++;
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", true);
        response.put("filter", Map.of("category", category == null ? "" : category, "limit", limit));

        // category rollup
        List<Map<String, Object>> categoryRollup = new ArrayList<>();
        byCategory.forEach((cat, cell) -> {
            long sum = latencySumByCategory.getOrDefault(cat, 0L);
            long cnt = latencyCountByCategory.getOrDefault(cat, 0L);
            long avg = cnt > 0 ? sum / cnt : 0L;
            double successRate = cell[0] == 0 ? 0 : (cell[1] / (double) cell[0]) * 100.0;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("category", cat);
            row.put("total", cell[0]);
            row.put("completed", cell[1]);
            row.put("failed", cell[2]);
            row.put("successRate", Math.round(successRate * 10.0) / 10.0);
            row.put("avgLatencyMs", avg);
            categoryRollup.add(row);
        });
        response.put("byCategory", categoryRollup);

        // trend
        List<Map<String, Object>> trendOut = new ArrayList<>();
        trend.forEach((day, cell) -> trendOut.add(Map.of(
            "date", day.toString(),
            "tasks", cell[0],
            "failures", cell[1])));
        response.put("trend", trendOut);

        // top agents
        List<Map<String, Object>> topAgents = new ArrayList<>();
        agentTaskCounts.entrySet().stream()
            .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
            .limit(10)
            .forEach(e -> topAgents.add(Map.of("agent", e.getKey(), "tasks", e.getValue())));
        response.put("topAgents", topAgents);

        ctx.status(200).json(response);
    }

    private static String resolveCategory(BusinessRunRecord run, BusinessTemplateService templateService) {
        // Prefer scenario.metadata.templateCategory; fallback to template lookup; default "general"
        Map<String, Object> metadata = run.getMetadata();
        if (metadata != null) {
            Object tc = metadata.get("templateCategory");
            if (tc instanceof String s && !s.isBlank()) return s;
        }
        String scenarioId = run.getScenarioId();
        if (scenarioId != null) {
            var sc = templateService.getScenario(scenarioId);
            if (sc.isPresent() && sc.get().getCategory() != null) return sc.get().getCategory();
        }
        return "general";
    }

    private static String lower(String v) {
        return v == null ? null : v.toLowerCase(Locale.ROOT);
    }

    private static int parseIntOrDefault(String raw, int fallback) {
        if (raw == null) return fallback;
        try { return Integer.parseInt(raw); } catch (NumberFormatException ex) { return fallback; }
    }

    @SuppressWarnings("unused")
    static JSONObject parseBody(Context ctx) {
        String body = ctx.body();
        if (body == null || body.isBlank()) return new JSONObject();
        return JSON.parseObject(body);
    }
}
