package com.nousresearch.hermes.dashboard;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.nousresearch.hermes.dashboard.handlers.*;
import com.nousresearch.hermes.config.HermesConfig;
import com.nousresearch.hermes.tenant.core.TenantManager;
import io.javalin.Javalin;
import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Hermes Dashboard Web Server.
 * Provides REST API for Web Dashboard and TUI, with session management,
 * configuration, and static asset serving.
 */
public class DashboardServer {
    private static final Logger logger = LoggerFactory.getLogger(DashboardServer.class);

    // Server configuration
    private final int port;
    private final String host;
    private final HermesConfig config;
    private Javalin app;
    private volatile boolean running = false;

    // Security
    private final String sessionToken;
    private final Set<String> publicApiPaths = Set.of(
        "/api/status",
        "/api/config/defaults",
        "/api/config/schema",
        "/api/model/info",
        "/api/dashboard/themes",
        "/api/dashboard/plugins",
        "/api/dashboard/plugins/rescan"
    );
    private final Set<String> loopbackHosts = Set.of("localhost", "127.0.0.1", "::1");

    // Rate limiting for reveal endpoint
    private final ConcurrentHashMap<String, Long> revealTimestamps = new ConcurrentHashMap<>();
    private static final int REVEAL_MAX_PER_WINDOW = 5;
    private static final long REVEAL_WINDOW_SECONDS = 30;

    // Handlers
    private final ConfigHandler configHandler;
    private final SessionHandler sessionHandler;
    private final EnvHandler envHandler;
    private final LogsHandler logsHandler;
    private final SkillsHandler skillsHandler;
    private final ToolsHandler toolsHandler;
    private final GatewayHandler gatewayHandler;
    private final CronHandler cronHandler;
    private final OAuthProvidersHandler oauthProvidersHandler;
    
    // Tenant Manager
    private final TenantManager tenantManager;
    private final Supplier<GatewayRuntimeStatus> gatewayStatusSupplier;

    public DashboardServer(int port, String host, HermesConfig config) {
        this(port, host, config, new TenantManager(), GatewayRuntimeStatus::disconnected);
    }
    
    public DashboardServer(int port, String host, HermesConfig config, TenantManager tenantManager) {
        this(port, host, config, tenantManager, GatewayRuntimeStatus::disconnected);
    }

    public DashboardServer(int port, String host, HermesConfig config,
                           TenantManager tenantManager,
                           Supplier<GatewayRuntimeStatus> gatewayStatusSupplier) {
        this.port = port;
        this.host = host;
        this.config = config;
        this.tenantManager = tenantManager;
        this.gatewayStatusSupplier = gatewayStatusSupplier != null
            ? gatewayStatusSupplier
            : GatewayRuntimeStatus::disconnected;
        this.sessionToken = generateSessionToken();

        // Initialize handlers
        this.configHandler = new ConfigHandler(config);
        this.sessionHandler = new SessionHandler();
        this.envHandler = new EnvHandler();
        this.logsHandler = new LogsHandler();
        this.skillsHandler = new SkillsHandler();
        this.toolsHandler = new ToolsHandler();
        this.gatewayHandler = new GatewayHandler();
        this.cronHandler = new CronHandler();
        this.oauthProvidersHandler = new OAuthProvidersHandler();

        logger.info("Dashboard session token generated (length: {})", sessionToken.length());
    }

    /**
     * Generate a secure random session token.
     */
    private String generateSessionToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String getSessionToken() {
        return sessionToken;
    }

    /**
     * Start the dashboard server.
     */
    public void start() {
        if (running) {
            logger.warn("Dashboard server already running");
            return;
        }

        app = Javalin.create(this::configureJavalin);

        // Register middleware
        registerMiddleware();

        // Register API routes
        registerApiRoutes();

        // Register static asset routes
        registerStaticRoutes();

        // Start server
        app.start(host, port);
        running = true;

        logger.info("Dashboard server started on http://{}:{}", host, port);
    }

    /**
     * Stop the dashboard server.
     */
    public void stop() {
        running = false;
        if (app != null) {
            app.stop();
        }
        logger.info("Dashboard server stopped");
    }

    /**
     * Configure Javalin settings.
     */
    private void configureJavalin(JavalinConfig config) {
        config.showJavalinBanner = false;
        config.bundledPlugins.enableCors(cors -> {
            cors.addRule(corsRule -> {
                corsRule.allowHost("http://localhost:" + port);
                corsRule.allowHost("http://127.0.0.1:" + port);
                corsRule.allowCredentials = true;
            });
        });
    }

    /**
     * Register middleware for security.
     */
    private void registerMiddleware() {
        // Host header validation middleware (DNS rebinding protection)
        app.before(ctx -> {
            String hostHeader = ctx.header("Host");
            if (!isAcceptedHost(hostHeader)) {
                ctx.status(400).result(JSON.toJSONString(new JSONObject()
                    .fluentPut("detail", "Invalid Host header. Dashboard requests must use the hostname the server was bound to.")));
                return;
            }

            // Auth middleware for /api/ routes
            String path = ctx.path();
            if (path.startsWith("/api/") && !publicApiPaths.contains(path) && !path.startsWith("/api/plugins/")) {
                String auth = ctx.header("Authorization");
                String expected = "Bearer " + sessionToken;

                if (auth == null || !hmacCompare(auth, expected)) {
                    ctx.status(401).result(JSON.toJSONString(new JSONObject()
                        .fluentPut("detail", "Unauthorized")));
                    return;
                }
            }
        });
    }

    /**
     * Constant-time string comparison to prevent timing attacks.
     */
    private boolean hmacCompare(String a, String b) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(new byte[32], "HmacSHA256"));
            byte[] hashA = mac.doFinal(a.getBytes(StandardCharsets.UTF_8));
            byte[] hashB = mac.doFinal(b.getBytes(StandardCharsets.UTF_8));
            return java.util.Arrays.equals(hashA, hashB);
        } catch (Exception e) {
            // Fallback to standard equals (less secure but functional)
            return a.equals(b);
        }
    }

    /**
     * Validate Host header against bound interface.
     */
    private boolean isAcceptedHost(String hostHeader) {
        if (hostHeader == null || hostHeader.isEmpty()) {
            return false;
        }

        // Strip port suffix
        String hostOnly;
        if (hostHeader.startsWith("[")) {
            int close = hostHeader.indexOf("]");
            hostOnly = close != -1 ? hostHeader.substring(1, close) : hostHeader.replace("[", "").replace("]", "");
        } else {
            hostOnly = hostHeader.contains(":") ? hostHeader.substring(0, hostHeader.lastIndexOf(":")) : hostHeader;
        }
        hostOnly = hostOnly.toLowerCase();

        // 0.0.0.0 bind means all interfaces (operator opted in)
        if (host.equals("0.0.0.0") || host.equals("::")) {
            return true;
        }

        // Loopback bind: accept loopback names
        String boundLc = host.toLowerCase();
        if (loopbackHosts.contains(boundLc)) {
            return loopbackHosts.contains(hostOnly);
        }

        // Explicit non-loopback bind: require exact match
        return hostOnly.equals(boundLc);
    }

    /**
     * Register all API routes.
     */
    private void registerApiRoutes() {
        // Health check
        app.get("/health", ctx -> ctx.result("OK"));

        // Status API
        app.get("/api/status", this::handleStatus);

        // Config API
        app.get("/api/config", configHandler::getConfig);
        app.put("/api/config", configHandler::updateConfig);
        app.get("/api/config/defaults", configHandler::getDefaults);
        app.get("/api/config/schema", configHandler::getSchema);
        app.get("/api/config/raw", configHandler::getRawConfig);
        app.put("/api/config/raw", configHandler::updateRawConfig);

        // Model info
        app.get("/api/model/info", configHandler::getModelInfo);

        // Environment variables API
        app.get("/api/env", envHandler::getEnvVars);
        app.put("/api/env", envHandler::setEnvVar);
        app.delete("/api/env", envHandler::deleteEnvVar);
        app.post("/api/env/reveal", envHandler::revealEnvVar);

        // OAuth provider status API
        app.get("/api/providers/oauth", oauthProvidersHandler::listProviders);
        app.delete("/api/providers/oauth/{providerId}", oauthProvidersHandler::disconnectProvider);
        app.post("/api/providers/oauth/{providerId}/start", oauthProvidersHandler::startLogin);
        app.post("/api/providers/oauth/{providerId}/submit", oauthProvidersHandler::submitCode);
        app.get("/api/providers/oauth/{providerId}/poll/{sessionId}", oauthProvidersHandler::pollSession);
        app.delete("/api/providers/oauth/sessions/{sessionId}", oauthProvidersHandler::cancelSession);

        // Sessions API
        app.get("/api/sessions", sessionHandler::getSessions);
        app.get("/api/sessions/search", sessionHandler::searchSessions);
        app.get("/api/sessions/{id}/messages", sessionHandler::getSessionMessages);
        app.delete("/api/sessions/{id}", sessionHandler::deleteSession);

        // Logs API
        app.get("/api/logs", logsHandler::getLogs);
        app.get("/api/logs/files", logsHandler::getLogFiles);

        // Skills API
        app.get("/api/skills", skillsHandler::getSkills);
        app.put("/api/skills/toggle", skillsHandler::toggleSkill);

        // Tools API
        app.get("/api/tools/toolsets", toolsHandler::getToolsets);
        app.get("/api/tools", toolsHandler::getTools);

        // Gateway control API
        app.post("/api/gateway/restart", gatewayHandler::restartGateway);
        app.post("/api/hermes/update", gatewayHandler::updateHermes);
        app.get("/api/actions/{name}/status", gatewayHandler::getActionStatus);

        // Analytics API (placeholder)
        app.get("/api/analytics/usage", ctx -> {
            ctx.json(new JSONObject()
                .fluentPut("daily", new java.util.ArrayList<>())
                .fluentPut("by_model", new java.util.ArrayList<>())
                .fluentPut("totals", new JSONObject())
                .fluentPut("skills", new JSONObject()));
        });

        // Cron jobs API
        app.get("/api/cron/jobs", cronHandler::listJobs);
        app.post("/api/cron/jobs", cronHandler::createJob);
        app.post("/api/cron/jobs/{id}/pause", cronHandler::pauseJob);
        app.post("/api/cron/jobs/{id}/resume", cronHandler::resumeJob);
        app.post("/api/cron/jobs/{id}/trigger", cronHandler::triggerJob);
        app.delete("/api/cron/jobs/{id}", cronHandler::deleteJob);

        // ========== Tenant Management APIs ==========
        TenantDashboardIntegration.registerRoutes(app, tenantManager);

        // Dashboard themes API
        app.get("/api/dashboard/themes", ctx -> {
            ctx.json(new JSONObject()
                .fluentPut("active", "default")
                .fluentPut("themes", java.util.List.of(
                    new JSONObject().fluentPut("name", "default").fluentPut("label", "Default"),
                    new JSONObject().fluentPut("name", "midnight").fluentPut("label", "Midnight"),
                    new JSONObject().fluentPut("name", "ember").fluentPut("label", "Ember")
                )));
        });
        app.put("/api/dashboard/theme", ctx -> {
            JSONObject body = ctx.body().isBlank() ? new JSONObject() : JSON.parseObject(ctx.body());
            String theme = body.getString("name");
            if (theme == null || theme.isBlank()) {
                theme = "default";
            }
            ctx.json(new JSONObject().fluentPut("ok", true).fluentPut("theme", theme));
        });

        // Plugins API
        app.get("/api/dashboard/plugins", ctx -> ctx.json(new java.util.ArrayList<>()));
        app.get("/api/dashboard/plugins/rescan", ctx -> ctx.json(new JSONObject()
            .fluentPut("ok", true)
            .fluentPut("count", 0)));
    }

    /**
     * Register static asset routes.
     */
    private void registerStaticRoutes() {
        // Serve static assets from the configured or detected dashboard build directory.
        java.nio.file.Path webDist = resolveWebDistPath();

        if (java.nio.file.Files.exists(webDist)) {
            logger.info("Serving dashboard static assets from {}", webDist);
            // Serve static files
            app.get("/assets/*", ctx -> serveStaticFile(ctx, webDist.resolve("assets")));
            app.get("/fonts/*", ctx -> serveStaticFile(ctx, webDist.resolve("fonts")));
            app.get("/ds-assets/*", ctx -> serveStaticFile(ctx, webDist.resolve("ds-assets")));

            // Serve index.html for all other routes (SPA fallback)
            app.get("/*", ctx -> {
                String path = ctx.path();
                if (path.startsWith("/api/") || path.startsWith("/health")) {
                    return; // Let API routes handle
                }

                java.nio.file.Path indexPath = webDist.resolve("index.html");
                if (java.nio.file.Files.exists(indexPath)) {
                    String html = java.nio.file.Files.readString(indexPath);
                    // Inject session token
                    html = html.replace("<head>",
                        "<head><script>window.__HERMES_SESSION_TOKEN__=\"" + sessionToken + "\";</script>");
                    ctx.contentType("text/html");
                    ctx.result(html);
                } else {
                    ctx.status(404).result("index.html not found");
                }
            });
        } else {
            logger.warn("Web dist directory not found: {}. Static assets will not be served.", webDist);
        }
    }


    java.nio.file.Path resolveWebDistPath() {
        String explicit = System.getenv("HERMES_WEB_DIST");
        if (explicit != null && !explicit.isBlank()) {
            return java.nio.file.Path.of(explicit).toAbsolutePath().normalize();
        }

        java.util.List<java.nio.file.Path> candidates = java.util.List.of(
            java.nio.file.Path.of("web_dist"),
            java.nio.file.Path.of("web", "dist"),
            java.nio.file.Path.of("frontend", "dist")
        );

        for (java.nio.file.Path candidate : candidates) {
            java.nio.file.Path normalized = candidate.toAbsolutePath().normalize();
            if (java.nio.file.Files.exists(normalized.resolve("index.html"))) {
                return normalized;
            }
        }

        return candidates.get(0).toAbsolutePath().normalize();
    }

    private void serveStaticFile(Context ctx, java.nio.file.Path basePath) {
        try {
            String requestPath = ctx.path();
            String relativePath = requestPath.substring(requestPath.indexOf('/', 1));
            java.nio.file.Path filePath = basePath.resolve(relativePath.substring(1)).normalize();

            // Security check: ensure file is within basePath
            if (!filePath.startsWith(basePath)) {
                ctx.status(403).result("Forbidden");
                return;
            }

            if (java.nio.file.Files.exists(filePath) && java.nio.file.Files.isRegularFile(filePath)) {
                String contentType = getContentType(filePath.toString());
                ctx.contentType(contentType);
                ctx.result(java.nio.file.Files.readAllBytes(filePath));
            } else {
                ctx.status(404).result("Not found");
            }
        } catch (Exception e) {
            logger.error("Error serving static file: {}", e.getMessage());
            ctx.status(500).result("Error");
        }
    }

    private String getContentType(String filename) {
        if (filename.endsWith(".js")) return "application/javascript";
        if (filename.endsWith(".css")) return "text/css";
        if (filename.endsWith(".html")) return "text/html";
        if (filename.endsWith(".json")) return "application/json";
        if (filename.endsWith(".png")) return "image/png";
        if (filename.endsWith(".jpg") || filename.endsWith(".jpeg")) return "image/jpeg";
        if (filename.endsWith(".svg")) return "image/svg+xml";
        if (filename.endsWith(".woff2")) return "font/woff2";
        if (filename.endsWith(".woff")) return "font/woff";
        if (filename.endsWith(".ttf")) return "font/ttf";
        return "application/octet-stream";
    }

    /**
     * Get dashboard status.
     */
    private void handleStatus(Context ctx) {
        ctx.json(buildStatus());
    }

    JSONObject buildStatus() {
        JSONObject status = new JSONObject();
        status.put("version", "0.1.0");
        status.put("release_date", "2026-04-23");
        status.put("hermes_home", System.getProperty("user.home") + "/.hermes");
        status.put("config_path", System.getProperty("user.home") + "/.hermes/config.yaml");
        status.put("env_path", System.getProperty("user.home") + "/.hermes/.env");
        status.put("config_version", 1);
        status.put("latest_config_version", 1);
        status.put("active_sessions", sessionHandler.getActiveSessionCount());

        GatewayRuntimeStatus gatewayStatus = gatewayStatusSupplier.get();
        JSONObject gatewayPlatforms = new JSONObject();
        for (String platform : gatewayStatus.platforms()) {
            gatewayPlatforms.put(platform, true);
        }
        status.put("gateway_running", gatewayStatus.running());
        status.put("gateway_pid", (Integer) null);
        status.put("gateway_state", gatewayStatus.state());
        status.put("gateway_health_url", gatewayStatus.healthUrl());
        status.put("gateway_exit_reason", gatewayStatus.exitReason());
        status.put("gateway_updated_at", gatewayStatus.updatedAt() != null ? gatewayStatus.updatedAt().toString() : null);
        status.put("gateway_platforms", gatewayPlatforms);
        status.put("gateway_port", gatewayStatus.port());
        // Add tenant info
        status.put("multi_tenant_enabled", true);
        status.put("tenant_count", tenantManager.getAllTenants().size());

        return status;
    }
    
}
