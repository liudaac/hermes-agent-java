package com.nousresearch.hermes.dashboard.handlers;

import com.nousresearch.hermes.org.observe.AgentTrace;
import com.nousresearch.hermes.collaboration.CapabilityScorer;
import com.nousresearch.hermes.tenant.core.TenantContext;
import com.nousresearch.hermes.tenant.core.TenantManager;
import com.nousresearch.hermes.tenant.audit.AuditEvent;
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
                row.put("agent_roles", t.listAgentRoles().entrySet().stream().map(e -> {
                    CapabilityScorer.clearExpiredManualOverride(e.getValue());
                    Map<String, Object> role = new LinkedHashMap<>(e.getValue().toMap());
                    role.put("agent", e.getKey());
                    return role;
                }).toList());
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


    /** POST /api/org/control/intents/{tenantId}/{runId}/replay */
    public void replayIntent(Context ctx) {
        TenantContext tenant = requireTenant(ctx.pathParam("tenantId"));
        Map<String, Object> body = parseJsonBody(ctx);
        String actor = operatorActor(ctx, body);
        String reason = stringOrDefault(body.get("reason"), "");
        String parentRunId = ctx.pathParam("runId");
        var run = tenant.getIntentOrchestrator().replayFailures(parentRunId);
        tenant.getAuditLogger().log(AuditEvent.CONTROL_INTENT_REPLAYED, Map.of(
            "tenantId", tenant.getTenantId(),
            "actor", actor,
            "reason", reason,
            "parentRunId", parentRunId,
            "newRunId", run.runId,
            "timestamp", System.currentTimeMillis()
        ));
        ctx.json(Map.of(
            "ok", true,
            "action", "replay_failed",
            "tenant_id", tenant.getTenantId(),
            "parent_run_id", ctx.pathParam("runId"),
            "run", run.toMap()
        ));
    }

    /** POST /api/org/control/intents/{tenantId}/{runId}/reroute */
    public void rerouteIntent(Context ctx) {
        TenantContext tenant = requireTenant(ctx.pathParam("tenantId"));
        Map<String, Object> body = parseJsonBody(ctx);
        String subtask = stringValue(body.get("subtask"));
        String targetAgent = stringValue(body.get("target_agent"));
        String actor = operatorActor(ctx, body);
        String reason = stringOrDefault(body.get("reason"), "");
        String parentRunId = ctx.pathParam("runId");
        var run = tenant.getIntentOrchestrator().reroute(parentRunId, subtask, targetAgent);
        tenant.getAuditLogger().log(AuditEvent.CONTROL_INTENT_REROUTED, Map.of(
            "tenantId", tenant.getTenantId(),
            "actor", actor,
            "reason", reason,
            "parentRunId", parentRunId,
            "newRunId", run.runId,
            "subtask", subtask != null ? subtask : "",
            "targetAgent", targetAgent != null ? targetAgent : "",
            "timestamp", System.currentTimeMillis()
        ));
        ctx.json(Map.of(
            "ok", true,
            "action", "reroute",
            "tenant_id", tenant.getTenantId(),
            "parent_run_id", ctx.pathParam("runId"),
            "run", run.toMap()
        ));
    }


    /** POST /api/org/control/agents/{tenantId}/{agentId}/override */
    public void agentOverride(Context ctx) {
        TenantContext tenant = requireTenant(ctx.pathParam("tenantId"));
        String agentId = ctx.pathParam("agentId");
        var role = tenant.getAgentRole(agentId);
        if (role == null) {
            throw new IllegalArgumentException("Unknown agent role: " + agentId);
        }
        Map<String, Object> body = parseJsonBody(ctx);
        String actor = operatorActor(ctx, body);
        String reason = stringOrDefault(body.get("reason"), "");
        String mode = stringValue(body.get("mode"));
        if (mode == null || mode.isBlank() || "normal".equalsIgnoreCase(mode)) {
            role.removeMetric("manual_disabled");
            role.removeMetric("manual_penalty");
            role.removeMetric("manual_expires_at");
            mode = "normal";
        } else if ("disabled".equalsIgnoreCase(mode)) {
            role.updateMetric("manual_disabled", true);
            role.removeMetric("manual_penalty");
            applyOverrideTtl(role, body);
            mode = "disabled";
        } else if ("deprioritized".equalsIgnoreCase(mode) || "deprioritize".equalsIgnoreCase(mode)) {
            role.updateMetric("manual_disabled", false);
            role.updateMetric("manual_penalty", parseDouble(body.get("penalty"), 1.5));
            applyOverrideTtl(role, body);
            mode = "deprioritized";
        } else {
            throw new IllegalArgumentException("Unsupported override mode: " + mode);
        }
        tenant.getAuditLogger().log(AuditEvent.CONTROL_AGENT_OVERRIDE_CHANGED, Map.of(
            "tenantId", tenant.getTenantId(),
            "actor", actor,
            "reason", reason,
            "agent", agentId,
            "mode", mode,
            "manualDisabled", role.getMetrics().getOrDefault("manual_disabled", false),
            "manualPenalty", role.getMetrics().getOrDefault("manual_penalty", 0),
            "manualExpiresAt", role.getMetrics().getOrDefault("manual_expires_at", 0),
            "timestamp", System.currentTimeMillis()
        ));
        tenant.save();
        ctx.json(Map.of(
            "ok", true,
            "tenant_id", tenant.getTenantId(),
            "agent", agentId,
            "mode", mode,
            "role", role.toMap()
        ));
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

    private TenantContext requireTenant(String tenantId) {
        TenantContext tenant = tenantManager.getTenant(tenantId);
        if (tenant == null) {
            throw new IllegalArgumentException("Unknown tenant: " + tenantId);
        }
        return tenant;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseJsonBody(Context ctx) {
        String body = ctx.body();
        if (body == null || body.isBlank()) return Map.of();
        try {
            return ctx.bodyAsClass(Map.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid JSON body: " + e.getMessage(), e);
        }
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String stringOrDefault(Object value, String fallback) {
        String s = stringValue(value);
        return s == null || s.isBlank() ? fallback : s;
    }

    private static String operatorActor(Context ctx, Map<String, Object> body) {
        String fromBody = stringValue(body.get("actor"));
        if (fromBody != null && !fromBody.isBlank()) return fromBody;
        String header = ctx.header("X-Hermes-Operator");
        if (header != null && !header.isBlank()) return header;
        header = ctx.header("X-Operator");
        if (header != null && !header.isBlank()) return header;
        String query = ctx.queryParam("actor");
        return query != null && !query.isBlank() ? query : "dashboard";
    }

    private static void applyOverrideTtl(com.nousresearch.hermes.collaboration.AgentRole role, Map<String, Object> body) {
        long ttlMs = parseLong(body.get("ttl_ms"), 0L);
        Object expiresAt = body.get("expires_at");
        if (expiresAt != null) {
            long deadline = parseLong(expiresAt, 0L);
            if (deadline > 0) role.updateMetric("manual_expires_at", deadline);
            else role.removeMetric("manual_expires_at");
            return;
        }
        if (ttlMs > 0) role.updateMetric("manual_expires_at", System.currentTimeMillis() + ttlMs);
        else role.removeMetric("manual_expires_at");
    }

    private static long parseLong(Object value, long fallback) {
        if (value == null) return fallback;
        try {
            return value instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(value));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static double parseDouble(Object value, double fallback) {
        if (value == null) return fallback;
        try {
            return value instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(value));
        } catch (Exception ignored) {
            return fallback;
        }
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
