package com.nousresearch.hermes.dashboard.handlers;

import com.nousresearch.hermes.browser.BrowserBridgeConfig;
import com.nousresearch.hermes.browser.BrowserBridgeFactory;
import com.nousresearch.hermes.browser.BrowserApprovalRequest;
import com.nousresearch.hermes.browser.contract.BrowserBridgeContractVerifier;
import com.nousresearch.hermes.org.observe.AgentTrace;
import com.nousresearch.hermes.collaboration.CapabilityScorer;
import com.nousresearch.hermes.governance.ControlActionPolicy;
import io.javalin.http.ForbiddenResponse;
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

        long browserActions = 0;
        for (TenantContext t : tenants) {
            for (var entry : t.getAuditLogger().getRecentEvents(500)) {
                if (entry.event().name().startsWith("CONTROL_BROWSER_")) {
                    browserActions++;
                }
            }
        }

        ctx.json(Map.of(
            "tenants", tenants.size(),
            "teams", teams,
            "members", members,
            "intent_runs", runs,
            "traces", traces,
            "anomalies", anomalies,
            "browser_actions", browserActions,
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
        int limit = parseInt(ctx.queryParam("limit"), 50);
        int offset = parseInt(ctx.queryParam("offset"), 0);
        String tenantFilter = ctx.queryParam("tenantId");
        List<Map<String, Object>> allRows = new ArrayList<>();
        for (TenantContext t : tenants()) {
            if (tenantFilter != null && !tenantFilter.isBlank() && !tenantFilter.equals(t.getTenantId())) continue;
            for (var run : t.getIntentOrchestrator().listRuns()) {
                Map<String, Object> row = new LinkedHashMap<>(run.toMap());
                row.put("tenant_id", t.getTenantId());
                allRows.add(row);
            }
        }
        allRows.sort((a, b) -> Long.compare((long)b.getOrDefault("started_at", 0L), (long)a.getOrDefault("started_at", 0L)));
        int safeOffset = Math.max(0, offset);
        int safeLimit = limit <= 0 ? allRows.size() : limit;
        List<Map<String, Object>> page = safeOffset >= allRows.size()
            ? List.of()
            : allRows.subList(safeOffset, Math.min(allRows.size(), safeOffset + safeLimit));
        ctx.json(Map.of(
            "runs", page,
            "count", page.size(),
            "total", allRows.size(),
            "limit", safeLimit,
            "offset", safeOffset
        ));
    }


    /** POST /api/org/control/intents/{tenantId}/{runId}/replay */
    public void replayIntent(Context ctx) {
        TenantContext tenant = requireTenant(ctx.pathParam("tenantId"));
        Map<String, Object> body = parseJsonBody(ctx);
        String actor = operatorActor(ctx, body);
        String reason = stringOrDefault(body.get("reason"), "");
        assertAllowed(tenant, actor, ControlActionPolicy.Action.REPLAY_INTENT, reason, Map.of("runId", ctx.pathParam("runId")));
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
        assertAllowed(tenant, actor, ControlActionPolicy.Action.REROUTE_INTENT, reason, Map.of(
            "runId", ctx.pathParam("runId"),
            "subtask", subtask != null ? subtask : "",
            "targetAgent", targetAgent != null ? targetAgent : ""
        ));
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
        assertAllowed(tenant, actor, ControlActionPolicy.Action.OVERRIDE_AGENT, reason, Map.of("agent", agentId));
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
                    "time", a.time().toString(),
                    "suggestion", anomalySuggestion(a.type().name(), a.agentId(), a.message())
                ));
            }
        }
        rows.sort((a, b) -> ((String)b.get("time")).compareTo((String)a.get("time")));
        if (rows.size() > n) rows = rows.subList(0, n);
        ctx.json(Map.of("anomalies", rows, "count", rows.size()));
    }


    /** GET /api/org/control/audit?n=50 */
    public void audit(Context ctx) {
        int n = parseInt(ctx.queryParam("n"), 50);
        String tenantFilter = ctx.queryParam("tenantId");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (TenantContext t : tenants()) {
            if (tenantFilter != null && !tenantFilter.isBlank() && !tenantFilter.equals(t.getTenantId())) continue;
            for (var entry : t.getAuditLogger().getRecentEvents(n)) {
                String event = entry.event().name();
                if (!event.startsWith("CONTROL_")) continue;
                rows.add(Map.of(
                    "tenant_id", t.getTenantId(),
                    "event", event,
                    "time", entry.timestamp().toString(),
                    "details", entry.details()
                ));
            }
        }
        rows.sort((a, b) -> ((String)b.get("time")).compareTo((String)a.get("time")));
        if (rows.size() > n) rows = rows.subList(0, n);
        ctx.json(Map.of("audit", rows, "count", rows.size()));
    }


    static Map<String, Object> anomalySuggestion(String type, String agentId, String message) {
        String reason = "Anomaly detected: " + type + " - " + message;
        return switch (type) {
            case "ERROR_STORM", "HIGH_LATENCY", "MODEL_DEGRADATION", "BEHAVIOR_CHANGE" -> Map.of(
                "kind", "agent_override",
                "mode", "deprioritized",
                "target_agent", agentId,
                "ttl_ms", 60 * 60 * 1000,
                "label", "Deprioritize 1h",
                "reason", reason
            );
            case "COST_SPIKE" -> Map.of(
                "kind", "monitor",
                "label", "Monitor cost spike",
                "reason", reason
            );
            default -> Map.of(
                "kind", "monitor",
                "label", "Review anomaly",
                "reason", reason
            );
        };
    }

    private List<TenantContext> tenants() {
        return new ArrayList<>(tenantManager.getAllTenants().values());
    }


    private void assertAllowed(TenantContext tenant, String actor, ControlActionPolicy.Action action, String reason, Map<String, Object> context) {
        if (ControlActionPolicy.isAllowed(actor, action)) return;
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("tenantId", tenant.getTenantId());
        details.put("actor", actor);
        details.put("action", action.name());
        details.put("reason", reason != null ? reason : "");
        details.put("denyReason", ControlActionPolicy.denyReason(actor, action));
        details.put("timestamp", System.currentTimeMillis());
        details.putAll(context);
        tenant.getAuditLogger().log(AuditEvent.CONTROL_ACTION_DENIED, details);
        throw new ForbiddenResponse(details.get("denyReason").toString());
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



    /** GET /api/org/control/browser/approvals?n=50 */
    public void browserApprovals(Context ctx) {
        int n = parseInt(ctx.queryParam("n"), 50);
        BrowserApprovalRequest.Status status = parseApprovalStatus(ctx.queryParam("status"));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (TenantContext t : tenants()) {
            for (var request : t.getBrowserApprovalQueue().list(n, status)) {
                rows.add(request.toMap());
            }
        }
        rows.sort((a, b) -> ((String)b.get("created_at")).compareTo((String)a.get("created_at")));
        if (rows.size() > n) rows = rows.subList(0, n);
        ctx.json(Map.of(
            "approvals", rows,
            "count", rows.size(),
            "status", status != null ? status.name() : "ALL"
        ));
    }

    /** POST /api/org/control/browser/approvals/{tenantId}/{approvalId}/reject */
    public void rejectBrowserApproval(Context ctx) {
        TenantContext tenant = requireTenant(ctx.pathParam("tenantId"));
        Map<String, Object> body = parseJsonBody(ctx);
        String actor = operatorActor(ctx, body);
        String reason = stringOrDefault(body.get("reason"), "Operator rejected browser approval");
        assertAllowed(tenant, actor, ControlActionPolicy.Action.REJECT_BROWSER_ACTION, reason, Map.of());

        var updated = tenant.getBrowserApprovalQueue().update(ctx.pathParam("approvalId"), BrowserApprovalRequest.Status.REJECTED, actor, reason);
        if (updated == null) throw new IllegalArgumentException("Unknown browser approval: " + ctx.pathParam("approvalId"));
        tenant.getAuditLogger().log(AuditEvent.CONTROL_BROWSER_APPROVAL_REJECTED, Map.of(
            "tenantId", tenant.getTenantId(),
            "actor", actor,
            "approvalId", updated.id(),
            "action", updated.action().action(),
            "reason", reason,
            "timestamp", System.currentTimeMillis()
        ));
        ctx.json(Map.of("ok", true, "approval", updated.toMap()));
    }

    /** POST /api/org/control/browser/approvals/{tenantId}/{approvalId}/approve */
    public void approveBrowserApproval(Context ctx) {
        TenantContext tenant = requireTenant(ctx.pathParam("tenantId"));
        Map<String, Object> body = parseJsonBody(ctx);
        String actor = operatorActor(ctx, body);
        String reason = stringOrDefault(body.get("reason"), "Operator approved browser action once");
        assertAllowed(tenant, actor, ControlActionPolicy.Action.APPROVE_BROWSER_ACTION, reason, Map.of());

        var request = tenant.getBrowserApprovalQueue().get(ctx.pathParam("approvalId"));
        if (request == null) throw new IllegalArgumentException("Unknown browser approval: " + ctx.pathParam("approvalId"));
        if (request.status() != BrowserApprovalRequest.Status.PENDING) {
            throw new IllegalArgumentException("Browser approval is not pending: " + request.status());
        }
        tenant.getBrowserApprovalQueue().update(request.id(), BrowserApprovalRequest.Status.APPROVED, actor, reason);
        tenant.getAuditLogger().log(AuditEvent.CONTROL_BROWSER_APPROVAL_APPROVED, Map.of(
            "tenantId", tenant.getTenantId(),
            "actor", actor,
            "approvalId", request.id(),
            "action", request.action().action(),
            "reason", reason,
            "timestamp", System.currentTimeMillis()
        ));

        Map<String, Object> execution = executeApprovedBrowserAction(tenant, request, actor, reason);
        ctx.json(Map.of("ok", true, "approval_id", request.id(), "execution", execution));
    }

    private Map<String, Object> executeApprovedBrowserAction(TenantContext tenant, BrowserApprovalRequest request, String actor, String reason) {
        var action = request.action();
        var obs = tenant.getObservability();
        var trace = obs.startTrace("browser-bridge", tenant.getTenantId(), "browser_bridge:approved:" + action.action());
        long started = System.currentTimeMillis();
        trace.meta("browser_action", action.action())
            .meta("actor", actor)
            .meta("approval_id", request.id())
            .meta("reason", reason)
            .step(AgentTrace.Step.toolCall("browser_bridge", request.rawArgs().toString(), java.util.List.of("approved browser action"), 0.96, 0, 0));

        var result = tenant.getBrowserBridge().execute(action);
        long duration = System.currentTimeMillis() - started;
        trace.step(AgentTrace.Step.toolResult("browser_bridge", result.toMap().toString(), duration));
        if (!result.ok()) trace.step(AgentTrace.Step.error(result.message()));
        trace.end(result.ok() ? AgentTrace.Status.SUCCESS : AgentTrace.Status.FAILED);
        obs.completeTrace(trace);

        tenant.getBrowserApprovalQueue().update(request.id(), result.ok() ? BrowserApprovalRequest.Status.EXECUTED : BrowserApprovalRequest.Status.FAILED, actor, reason);
        Map<String, Object> actionAudit = new LinkedHashMap<>();
        actionAudit.put("tenantId", tenant.getTenantId());
        actionAudit.put("actor", actor);
        actionAudit.put("action", action.action());
        actionAudit.put("sessionId", result.sessionId() != null ? result.sessionId() : (action.sessionId() != null ? action.sessionId() : ""));
        actionAudit.put("url", result.url() != null ? result.url() : (action.url() != null ? action.url() : ""));
        actionAudit.put("ok", result.ok());
        actionAudit.put("reason", reason);
        actionAudit.put("approvalId", request.id());
        actionAudit.put("traceId", trace.getTraceId());
        actionAudit.put("durationMs", duration);
        actionAudit.put("timestamp", System.currentTimeMillis());
        tenant.getAuditLogger().log(AuditEvent.CONTROL_BROWSER_ACTION, actionAudit);

        Map<String, Object> payload = new LinkedHashMap<>(result.toMap());
        payload.put("trace_id", trace.getTraceId());
        payload.put("approval_id", request.id());
        return payload;
    }

    /** GET /api/org/control/browser/status */
    public void browserStatus(Context ctx) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (TenantContext t : tenants()) {
            Map<String, Object> row = new LinkedHashMap<>(t.getBrowserBridge().describe());
            row.put("tenant_id", t.getTenantId());
            if (!t.getBrowserContractReport().isEmpty()) row.put("contract_report", t.getBrowserContractReport());
            rows.add(row);
        }
        ctx.json(Map.of("browser_bridges", rows, "count", rows.size()));
    }

    /** POST /api/org/control/browser/{tenantId}/provider */
    public void configureBrowserProvider(Context ctx) {
        TenantContext tenant = requireTenant(ctx.pathParam("tenantId"));
        Map<String, Object> body = parseJsonBody(ctx);
        String actor = operatorActor(ctx, body);
        String reason = stringOrDefault(body.get("reason"), "Operator configured browser bridge provider");
        assertAllowed(tenant, actor, ControlActionPolicy.Action.CONFIGURE_BROWSER_BRIDGE, reason, Map.of());

        String provider = stringOrDefault(body.get("provider"), "mock").toLowerCase(Locale.ROOT).trim();
        String endpoint = stringOrDefault(body.get("endpoint"), "");
        int timeoutMs = (int) parseLong(body.get("timeout_ms"), 10000L);
        var config = new BrowserBridgeConfig(provider, endpoint, timeoutMs);
        var bridge = BrowserBridgeFactory.create(config);
        tenant.setBrowserBridge(bridge);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("tenantId", tenant.getTenantId());
        details.put("actor", actor);
        details.put("provider", provider);
        details.put("endpoint", endpoint);
        details.put("timeoutMs", timeoutMs);
        details.put("reason", reason);
        details.put("timestamp", System.currentTimeMillis());
        tenant.getAuditLogger().log(AuditEvent.CONTROL_BROWSER_BRIDGE_CONFIGURED, details);

        Map<String, Object> response = new LinkedHashMap<>(bridge.describe());
        response.put("ok", true);
        response.put("tenant_id", tenant.getTenantId());
        ctx.json(response);
    }



    /** POST /api/org/control/browser/{tenantId}/contract */
    public void browserContractTest(Context ctx) {
        TenantContext tenant = requireTenant(ctx.pathParam("tenantId"));
        Map<String, Object> body = parseJsonBody(ctx);
        String actor = operatorActor(ctx, body);
        String reason = stringOrDefault(body.get("reason"), "Operator ran browser bridge contract test");
        assertAllowed(tenant, actor, ControlActionPolicy.Action.CHECK_BROWSER_BRIDGE, reason, Map.of());

        long started = System.currentTimeMillis();
        String endpoint = stringOrDefault(tenant.getBrowserBridge().describe().get("endpoint"), "");
        var report = new BrowserBridgeContractVerifier(tenant.getBrowserBridge(), endpoint).verify();
        Map<String, Object> reportMap = new LinkedHashMap<>(report.toMap());
        reportMap.put("tenant_id", tenant.getTenantId());
        reportMap.put("duration_ms", System.currentTimeMillis() - started);
        reportMap.put("timestamp", java.time.Instant.now().toString());
        tenant.setBrowserContractReport(reportMap);
        tenant.getAuditLogger().log(AuditEvent.CONTROL_BROWSER_CONTRACT_TEST, Map.of(
            "tenantId", tenant.getTenantId(),
            "actor", actor,
            "ok", report.ok(),
            "endpoint", endpoint,
            "checks", report.checks().size(),
            "reason", reason,
            "timestamp", System.currentTimeMillis()
        ));
        ctx.json(reportMap);
    }

    /** POST /api/org/control/browser/{tenantId}/capabilities */
    public void browserCapabilities(Context ctx) {
        TenantContext tenant = requireTenant(ctx.pathParam("tenantId"));
        Map<String, Object> body = parseJsonBody(ctx);
        String actor = operatorActor(ctx, body);
        String reason = stringOrDefault(body.get("reason"), "Operator checked browser bridge capabilities");
        assertAllowed(tenant, actor, ControlActionPolicy.Action.CHECK_BROWSER_BRIDGE, reason, Map.of());
        Map<String, Object> capabilities = new LinkedHashMap<>(tenant.getBrowserBridge().capabilities());
        capabilities.put("tenant_id", tenant.getTenantId());
        ctx.json(capabilities);
    }

    /** POST /api/org/control/browser/{tenantId}/health */
    public void browserHealth(Context ctx) {
        TenantContext tenant = requireTenant(ctx.pathParam("tenantId"));
        Map<String, Object> body = parseJsonBody(ctx);
        String actor = operatorActor(ctx, body);
        String reason = stringOrDefault(body.get("reason"), "Operator checked browser bridge health");
        assertAllowed(tenant, actor, ControlActionPolicy.Action.CHECK_BROWSER_BRIDGE, reason, Map.of());

        long started = System.currentTimeMillis();
        var result = tenant.getBrowserBridge().healthCheck();
        long durationMs = System.currentTimeMillis() - started;

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("tenantId", tenant.getTenantId());
        details.put("actor", actor);
        details.put("ok", result.ok());
        details.put("message", result.message() != null ? result.message() : "");
        details.put("durationMs", durationMs);
        details.put("reason", reason);
        details.put("timestamp", System.currentTimeMillis());
        tenant.getAuditLogger().log(AuditEvent.CONTROL_BROWSER_HEALTH_CHECK, details);

        Map<String, Object> response = new LinkedHashMap<>(result.toMap());
        response.put("tenant_id", tenant.getTenantId());
        response.put("duration_ms", durationMs);
        response.put("provider", tenant.getBrowserBridge().describe());
        ctx.json(response);
    }

    /** GET /api/org/control/browser?n=50 */
    public void browserTimeline(Context ctx) {
        int n = parseInt(ctx.queryParam("n"), 50);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (TenantContext t : tenants()) {
            for (var entry : t.getAuditLogger().getRecentEvents(n * 2 + 20)) {
                String event = entry.event().name();
                if (!event.startsWith("CONTROL_BROWSER_")) continue;
                Map<String, Object> details = entry.details();
                boolean denied = event.equals("CONTROL_BROWSER_ACTION_DENIED");
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("tenant_id", t.getTenantId());
                row.put("event", event);
                row.put("denied", denied);
                row.put("time", entry.timestamp().toString());
                row.put("actor", detailString(details, "actor", "unknown"));
                row.put("action", detailString(details, "action", ""));
                row.put("url", detailString(details, "url", ""));
                row.put("target", detailString(details, "target", ""));
                row.put("session_id", detailString(details, "sessionId", ""));
                row.put("trace_id", detailString(details, "traceId", ""));
                row.put("reason", detailString(details, "reason", ""));
                row.put("deny_reason", detailString(details, "denyReason", ""));
                row.put("requires_confirmation", detailBoolean(details, "requiresConfirmation", false));
                row.put("ok", detailBoolean(details, "ok", !denied));
                rows.add(row);
            }
        }
        rows.sort((a, b) -> ((String)b.get("time")).compareTo((String)a.get("time")));
        if (rows.size() > n) rows = rows.subList(0, n);
        ctx.json(Map.of("browser_timeline", rows, "count", rows.size()));
    }


    private static String detailString(Map<String, Object> details, String key, String fallback) {
        Object value = details.get(key);
        if (value != null) return stringOrDefault(value, fallback);
        String raw = stringValue(details.get("raw"));
        if (raw == null || raw.isBlank()) return fallback;
        String needle = key + "=";
        int start = raw.indexOf(needle);
        if (start < 0) return fallback;
        start += needle.length();
        int comma = raw.indexOf(", ", start);
        int endBrace = raw.indexOf('}', start);
        int end = comma >= 0 ? comma : (endBrace >= 0 ? endBrace : raw.length());
        String parsed = raw.substring(start, end).trim();
        return parsed.isBlank() ? fallback : parsed;
    }

    private static boolean detailBoolean(Map<String, Object> details, String key, boolean fallback) {
        Object value = details.get(key);
        if (value instanceof Boolean b) return b;
        String parsed = value != null ? String.valueOf(value) : detailString(details, key, String.valueOf(fallback));
        return "true".equalsIgnoreCase(parsed) || "1".equals(parsed) || "yes".equalsIgnoreCase(parsed);
    }


    private static BrowserApprovalRequest.Status parseApprovalStatus(String raw) {
        if (raw == null || raw.isBlank() || raw.equalsIgnoreCase("ALL")) return null;
        try { return BrowserApprovalRequest.Status.valueOf(raw.trim().toUpperCase(Locale.ROOT)); }
        catch (Exception ignored) { return null; }
    }

    private static int parseInt(String raw, int fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try { return Integer.parseInt(raw); } catch (Exception ignored) { return fallback; }
    }
}
