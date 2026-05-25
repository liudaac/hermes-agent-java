package com.nousresearch.hermes.gateway;

import io.javalin.Javalin;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Backwards-compatibility shim for the legacy dashboard-style routes that the
 * Gateway HTTP server used to expose alongside its real responsibilities
 * (webhook + chat). The canonical implementation now lives in
 * {@code com.nousresearch.hermes.dashboard.DashboardServer}.
 *
 * <p>These routes return a stable {@code 410 Gone} payload pointing clients at
 * the Dashboard server, so that old clients fail loudly instead of getting back
 * fake data or silently drifting from the dashboard's API contract.</p>
 */
final class DeprecatedDashboardApi {
    private static final Logger logger = LoggerFactory.getLogger(DeprecatedDashboardApi.class);
    private static final String NOTICE =
        "Dashboard APIs moved to DashboardServer; this gateway no longer serves them.";
    private static final String CANONICAL = "DashboardServer (/api/...) — see docs/DASHBOARD.md";

    private DeprecatedDashboardApi() {
    }

    static void register(Javalin app) {
        // Config / sessions
        app.get("/api/config", DeprecatedDashboardApi::gone);
        app.post("/api/config", DeprecatedDashboardApi::gone);
        app.get("/api/config/schema", DeprecatedDashboardApi::gone);
        app.get("/api/sessions", DeprecatedDashboardApi::gone);
        app.get("/api/sessions/{id}/messages", DeprecatedDashboardApi::gone);

        // Tenants
        app.get("/api/tenants", DeprecatedDashboardApi::gone);
        app.post("/api/tenants", DeprecatedDashboardApi::gone);
        app.get("/api/tenants/{id}", DeprecatedDashboardApi::gone);
        app.delete("/api/tenants/{id}", DeprecatedDashboardApi::gone);
        app.post("/api/tenants/{id}/suspend", DeprecatedDashboardApi::gone);
        app.post("/api/tenants/{id}/resume", DeprecatedDashboardApi::gone);
        app.get("/api/tenants/{id}/quota", DeprecatedDashboardApi::gone);
        app.put("/api/tenants/{id}/quota", DeprecatedDashboardApi::gone);
        app.get("/api/tenants/{id}/usage", DeprecatedDashboardApi::gone);
        app.get("/api/tenants/{id}/security", DeprecatedDashboardApi::gone);
        app.put("/api/tenants/{id}/security", DeprecatedDashboardApi::gone);
        app.get("/api/tenants/{id}/audit", DeprecatedDashboardApi::gone);

        // Skills
        app.get("/api/skills", DeprecatedDashboardApi::gone);
        app.put("/api/skills/{name}", DeprecatedDashboardApi::gone);

        // Cron
        app.get("/api/cron", DeprecatedDashboardApi::gone);
        app.post("/api/cron", DeprecatedDashboardApi::gone);
        app.put("/api/cron/{id}", DeprecatedDashboardApi::gone);
        app.delete("/api/cron/{id}", DeprecatedDashboardApi::gone);

        // Env
        app.get("/api/env", DeprecatedDashboardApi::gone);
        app.put("/api/env", DeprecatedDashboardApi::gone);
        app.delete("/api/env/{key}", DeprecatedDashboardApi::gone);

        // Actions / logs / analytics
        app.post("/api/actions/restart-gateway", DeprecatedDashboardApi::gone);
        app.post("/api/actions/update", DeprecatedDashboardApi::gone);
        app.get("/api/actions/{name}/status", DeprecatedDashboardApi::gone);
        app.get("/api/logs", DeprecatedDashboardApi::gone);
        app.get("/api/analytics", DeprecatedDashboardApi::gone);

        logger.info("Registered deprecated dashboard API shim on gateway server (returns 410 Gone)");
    }

    private static void gone(Context ctx) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", NOTICE);
        body.put("canonical", CANONICAL);
        body.put("path", ctx.path());
        body.put("deprecated", true);
        ctx.status(410).json(body);
    }
}
