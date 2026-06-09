package com.nousresearch.hermes.dashboard.handlers;

import com.nousresearch.hermes.org.observe.AgentTrace;
import com.nousresearch.hermes.tenant.core.TenantContext;
import com.nousresearch.hermes.tenant.core.TenantManager;
import io.javalin.http.Context;

import java.time.Duration;
import java.util.*;

/**
 * Org Control Center API.
 *
 * <p>Read-only dashboard endpoints that make the AI-native organization loop
 * visible as a product surface: teams, intent runs, traces, evolution lessons,
 * and anomalies across all loaded tenants.</p>
 */
public class OrgControlCenterHandler {
    private final TenantManager tenantManager;

    public OrgControlCenterHandler(TenantManager tenantManager) {
        this.tenantManager = tenantManager;
    }

    /** GET /api/org/control/overview */
    public void overview(Context ctx) {
        var tenants = tenants();
        int teams = 0;
        int members = 0;
        int runs = 0;
        int traces = 0;
        int anomalies = 0;
        long failures = 0;

        List<Map<String, Object>> tenantRows = new ArrayList<>();
        for (TenantContext t : tenants) {
            int tenantTeams = t.getTeamManager().teamCount();
            int tenantMembers = t.getTeamManager().listTeams().stream().mapToInt(team -> team.size()).sum();
            int tenantRuns = t.getIntentOrchestrator().listRuns().size();
            int tenantTraces = t.getObservability().getAllRecentTraces(1000).size();
            int tenantAnomalies = t.getObservability().getRecentAnomalies(1000).size();
            long tenantFailures = t.getEvolutionEngine().getTotalFailures();

            teams += tenantTeams;
            members += tenantMembers;
            runs += tenantRuns;
            traces += tenantTraces;
            anomalies += tenantAnomalies;
            failures += tenantFailures;

            tenantRows.add(Map.of(
                "tenant_id", t.getTenantId(),
                "state", t.getState().name(),
                "active", t.isActive(),
                "teams", tenantTeams,
                "members", tenantMembers,
                "intent_runs", tenantRuns,
                "traces", tenantTraces,
                "anomalies", tenantAnomalies,
                "evolution_failures", tenantFailures
            ));
        }

        ctx.json(Map.of(
            "tenants", tenants.size(),
            "teams", teams,
            "members", members,
            "intent_runs", runs,
            "traces", traces,
            "anomalies", anomalies,
            "evolution_failures", failures,
            "tenant_rows", tenantRows
        ));
    }

    /** GET /api/org/control/teams */
    public void teams(Context ctx) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (TenantContext t : tenants()) {
            for (var team : t.getTeamManager().listTeams()) {
                Map<String, Object> row = new LinkedHashMap<>(team.toMap());
                row.put("tenant_id", t.getTenantId());
                row.put("recent_activity", team.getRecentActivity(8).stream().map(a -> Map.of(
                    "type", a.type(),
                    "actor", a.actor() != null ? a.actor() : "",
                    "detail", a.detail(),
                    "time", a.timestamp().toString()
                )).toList());
                row.put("state_keys", new ArrayList<>(team.getState().keySet()));
                rows.add(row);
            }
        }
        ctx.json(Map.of("teams", rows, "count", rows.size()));
    }

    /** GET /api/org/control/intents */
    public void intents(Context ctx) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (TenantContext t : tenants()) {
            for (var run : t.getIntentOrchestrator().listRuns()) {
                Map<String, Object> row = new LinkedHashMap<>(run.toMap());
                row.put("tenant_id", t.getTenantId());
                rows.add(row);
            }
        }
        rows.sort((a, b) -> Long.compare((long)b.getOrDefault("started_at", 0L), (long)a.getOrDefault("started_at", 0L)));
        ctx.json(Map.of("runs", rows, "count", rows.size()));
    }

    /** GET /api/org/control/traces?n=50 */
    public void traces(Context ctx) {
        int n = parseInt(ctx.queryParam("n"), 50);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (TenantContext t : tenants()) {
            for (AgentTrace trace : t.getObservability().getAllRecentTraces(n)) {
                rows.add(traceRow(t.getTenantId(), trace));
            }
        }
        rows.sort((a, b) -> ((String)b.get("started_at")).compareTo((String)a.get("started_at")));
        if (rows.size() > n) rows = rows.subList(0, n);
        ctx.json(Map.of("traces", rows, "count", rows.size()));
    }

    /** GET /api/org/control/evolution */
    public void evolution(Context ctx) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (TenantContext t : tenants()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("tenant_id", t.getTenantId());
            row.putAll(t.getEvolutionEngine().getSummary());
            rows.add(row);
        }
        ctx.json(Map.of("evolution", rows, "count", rows.size()));
    }

    /** GET /api/org/control/anomalies?n=50 */
    public void anomalies(Context ctx) {
        int n = parseInt(ctx.queryParam("n"), 50);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (TenantContext t : tenants()) {
            for (var a : t.getObservability().getRecentAnomalies(n)) {
                rows.add(Map.of(
                    "tenant_id", t.getTenantId(),
                    "type", a.type().name(),
                    "agent", a.agentId(),
                    "message", a.message(),
                    "time", a.time().toString()
                ));
            }
        }
        rows.sort((a, b) -> ((String)b.get("time")).compareTo((String)a.get("time")));
        if (rows.size() > n) rows = rows.subList(0, n);
        ctx.json(Map.of("anomalies", rows, "count", rows.size()));
    }

    private List<TenantContext> tenants() {
        return new ArrayList<>(tenantManager.getAllTenants().values());
    }

    private static Map<String, Object> traceRow(String tenantId, AgentTrace t) {
        long duration = t.getEndTime() != null
            ? Duration.between(t.getStartTime(), t.getEndTime()).toMillis()
            : 0;
        return Map.of(
            "tenant_id", tenantId,
            "trace_id", t.getTraceId(),
            "agent", t.getAgentId(),
            "session", t.getSessionId(),
            "task", t.getTaskDescription(),
            "status", t.getStatus().name(),
            "steps", t.stepCount(),
            "errors", t.getErrorCount(),
            "duration_ms", duration,
            "started_at", t.getStartTime().toString()
        );
    }

    private static int parseInt(String raw, int fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try { return Integer.parseInt(raw); } catch (Exception ignored) { return fallback; }
    }
}
