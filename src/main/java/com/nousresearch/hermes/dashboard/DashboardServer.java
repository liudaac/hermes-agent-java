package com.nousresearch.hermes.dashboard;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.nousresearch.hermes.dashboard.handlers.*;
import com.nousresearch.hermes.config.HermesConfig;
import com.nousresearch.hermes.tenant.core.TenantManager;
import com.nousresearch.hermes.tenant.core.TenantContext;
import com.nousresearch.hermes.tenant.core.TenantProvisioningRequest;
import com.nousresearch.hermes.tenant.quota.TenantQuota;
import com.nousresearch.hermes.tenant.security.TenantSecurityPolicy;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
    
    // Tenant Manager
    private final TenantManager tenantManager;

    public DashboardServer(int port, String host, HermesConfig config) {
        this(port, host, config, new TenantManager());
    }
    
    public DashboardServer(int port, String host, HermesConfig config, TenantManager tenantManager) {
        this.port = port;
        this.host = host;
        this.config = config;
        this.tenantManager = tenantManager;
        this.sessionToken = generateSessionToken();

        // Initialize handlers
        this.configHandler = new ConfigHandler(config);
        this.sessionHandler = new SessionHandler();
        this.envHandler = new EnvHandler();
        this.logsHandler = new LogsHandler();
        this.skillsHandler = new SkillsHandler();
        this.toolsHandler = new ToolsHandler();
        this.gatewayHandler = new GatewayHandler();

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

        // Sessions API
        app.get("/api/sessions", sessionHandler::getSessions);
        app.get("/api/sessions/search", sessionHandler::searchSessions);
        app.get("/api/sessions/{id}/messages", sessionHandler::getSessionMessages);
        app.delete("/api/sessions/{id}", sessionHandler::deleteSession);

        // Logs API
        app.get("/api/logs", logsHandler::getLogs);

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

        // Cron jobs API (placeholder)
        app.get("/api/cron/jobs", ctx -> ctx.json(new java.util.ArrayList<>()));

        // ========== Tenant Management APIs ==========
        registerTenantRoutes();

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
        app.put("/api/dashboard/theme", ctx -> ctx.json(new JSONObject().fluentPut("ok", true)));

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
        // Serve static assets from web_dist directory
        String webDistPath = System.getenv().getOrDefault("HERMES_WEB_DIST", "web_dist");
        java.nio.file.Path webDist = java.nio.file.Path.of(webDistPath).toAbsolutePath().normalize();

        if (java.nio.file.Files.exists(webDist)) {
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
        JSONObject status = new JSONObject();
        status.put("version", "0.1.0");
        status.put("release_date", "2026-04-23");
        status.put("hermes_home", System.getProperty("user.home") + "/.hermes");
        status.put("config_path", System.getProperty("user.home") + "/.hermes/config.yaml");
        status.put("env_path", System.getProperty("user.home") + "/.hermes/.env");
        status.put("config_version", 1);
        status.put("latest_config_version", 1);
        status.put("active_sessions", sessionHandler.getActiveSessionCount());
        status.put("gateway_running", false);
        status.put("gateway_pid", (Integer) null);
        status.put("gateway_state", (String) null);
        status.put("gateway_health_url", (String) null);
        status.put("gateway_exit_reason", (String) null);
        status.put("gateway_updated_at", (String) null);
        status.put("gateway_platforms", new JSONObject());
        // Add tenant info
        status.put("multi_tenant_enabled", true);
        status.put("tenant_count", tenantManager.getAllTenants().size());

        ctx.json(status);
    }
    
    // ========== Tenant Management Methods ==========
    
    private void registerTenantRoutes() {
        // List all tenants
        app.get("/api/tenants", this::listTenants);
        
        // Create new tenant
        app.post("/api/tenants", this::createTenant);
        
        // Get tenant details
        app.get("/api/tenants/{tenantId}", this::getTenant);
        
        // Delete tenant
        app.delete("/api/tenants/{tenantId}", this::deleteTenant);
        
        // Suspend tenant
        app.post("/api/tenants/{tenantId}/suspend", this::suspendTenant);
        
        // Resume tenant
        app.post("/api/tenants/{tenantId}/resume", this::resumeTenant);
        
        // Get tenant quota
        app.get("/api/tenants/{tenantId}/quota", this::getTenantQuota);
        
        // Update tenant quota
        app.put("/api/tenants/{tenantId}/quota", this::updateTenantQuota);
        
        // Get tenant usage
        app.get("/api/tenants/{tenantId}/usage", this::getTenantUsage);
        
        // Get tenant security policy
        app.get("/api/tenants/{tenantId}/security", this::getTenantSecurity);
        
        // Update tenant security policy
        app.put("/api/tenants/{tenantId}/security", this::updateTenantSecurity);
        
        // Get tenant audit logs
        app.get("/api/tenants/{tenantId}/audit", this::getTenantAuditLogs);
        
        // Get tenant sessions
        app.get("/api/tenants/{tenantId}/sessions", this::getTenantSessions);
        
        // Get tenant skills
        app.get("/api/tenants/{tenantId}/skills", this::getTenantSkills);
    }
    
    private void listTenants(Context ctx) {
        java.util.List<Map<String, Object>> tenants = tenantManager.getAllTenants().entrySet().stream()
            .map(entry -> {
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("id", entry.getKey());
                info.put("state", entry.getValue().getState().name());
                info.put("createdAt", entry.getValue().getCreatedAt().toString());
                info.put("lastActivity", entry.getValue().getLastActivity().toString());
                info.put("activeAgents", entry.getValue().getActiveAgentCount());
                info.put("activeSessions", entry.getValue().getActiveSessionCount());
                return info;
            })
            .collect(java.util.stream.Collectors.toList());
        
        ctx.json(Map.of("tenants", tenants));
    }
    
    private void createTenant(Context ctx) {
        try {
            JSONObject body = JSON.parseObject(ctx.body());
            String tenantId = body.getString("id");
            String createdBy = body.getString("createdBy");
            
            if (tenantId == null || tenantId.isEmpty()) {
                ctx.status(400).json(Map.of("error", "Tenant ID is required"));
                return;
            }
            
            if (tenantManager.getTenant(tenantId) != null) {
                ctx.status(409).json(Map.of("error", "Tenant already exists: " + tenantId));
                return;
            }
            
            TenantProvisioningRequest request = TenantProvisioningRequest.builder(tenantId, 
                createdBy != null ? createdBy : "admin").build();
            
            TenantContext tenant = tenantManager.provisionTenant(request);
            
            ctx.json(Map.of(
                "success", true,
                "id", tenantId,
                "state", tenant.getState().name()
            ));
            logger.info("Created tenant: {}", tenantId);
        } catch (Exception e) {
            logger.error("Failed to create tenant: {}", e.getMessage(), e);
            ctx.status(500).json(Map.of("error", e.getMessage()));
        }
    }
    
    private void getTenant(Context ctx) {
        String tenantId = ctx.pathParam("tenantId");
        TenantContext tenant = tenantManager.getTenant(tenantId);
        
        if (tenant == null) {
            ctx.status(404).json(Map.of("error", "Tenant not found: " + tenantId));
            return;
        }
        
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("id", tenantId);
        info.put("state", tenant.getState().name());
        info.put("createdAt", tenant.getCreatedAt().toString());
        info.put("lastActivity", tenant.getLastActivity().toString());
        info.put("activeAgents", tenant.getActiveAgentCount());
        info.put("activeSessions", tenant.getActiveSessionCount());
        info.put("quota", tenant.getQuotaManager().getQuota().toMap());
        
        ctx.json(info);
    }
    
    private void deleteTenant(Context ctx) {
        String tenantId = ctx.pathParam("tenantId");
        TenantContext tenant = tenantManager.getTenant(tenantId);
        
        if (tenant == null) {
            ctx.status(404).json(Map.of("error", "Tenant not found: " + tenantId));
            return;
        }

        tenantManager.deleteTenant(tenantId);
        ctx.json(Map.of("success", true, "id", tenantId));
        logger.info("Deleted tenant: {}", tenantId);
    }
    
    private void suspendTenant(Context ctx) {
        String tenantId = ctx.pathParam("tenantId");
        TenantContext tenant = tenantManager.getTenant(tenantId);
        
        if (tenant == null) {
            ctx.status(404).json(Map.of("error", "Tenant not found: " + tenantId));
            return;
        }
        
        tenant.suspend("Manual suspension via dashboard");
        ctx.json(Map.of("success", true, "id", tenantId, "state", "SUSPENDED"));
    }
    
    private void resumeTenant(Context ctx) {
        String tenantId = ctx.pathParam("tenantId");
        TenantContext tenant = tenantManager.getTenant(tenantId);
        
        if (tenant == null) {
            ctx.status(404).json(Map.of("error", "Tenant not found: " + tenantId));
            return;
        }
        
        tenant.resume();
        ctx.json(Map.of("success", true, "id", tenantId, "state", "ACTIVE"));
    }
    
    private void getTenantQuota(Context ctx) {
        String tenantId = ctx.pathParam("tenantId");
        TenantContext tenant = tenantManager.getTenant(tenantId);
        
        if (tenant == null) {
            ctx.status(404).json(Map.of("error", "Tenant not found: " + tenantId));
            return;
        }
        
        ctx.json(tenant.getQuotaManager().getQuota().toMap());
    }
    
    private void updateTenantQuota(Context ctx) {
        String tenantId = ctx.pathParam("tenantId");
        TenantContext tenant = tenantManager.getTenant(tenantId);
        
        if (tenant == null) {
            ctx.status(404).json(Map.of("error", "Tenant not found: " + tenantId));
            return;
        }
        
        // Store quota in config
        JSONObject body = JSON.parseObject(ctx.body());
        tenant.getConfig().set("quota", body);
        tenant.getConfig().save();
        
        ctx.json(Map.of("success", true));
    }
    
    private void getTenantUsage(Context ctx) {
        String tenantId = ctx.pathParam("tenantId");
        TenantContext tenant = tenantManager.getTenant(tenantId);
        
        if (tenant == null) {
            ctx.status(404).json(Map.of("error", "Tenant not found: " + tenantId));
            return;
        }
        
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("storage", tenant.getQuotaManager().getStorageUsage());
        usage.put("memory", tenant.getQuotaManager().getMemoryUsage());
        usage.put("quota", tenant.getQuotaManager().getUsage());
        
        ctx.json(usage);
    }
    
    private void getTenantSecurity(Context ctx) {
        String tenantId = ctx.pathParam("tenantId");
        TenantContext tenant = tenantManager.getTenant(tenantId);
        
        if (tenant == null) {
            ctx.status(404).json(Map.of("error", "Tenant not found: " + tenantId));
            return;
        }
        
        TenantSecurityPolicy policy = tenant.getSecurityPolicy();
        Map<String, Object> security = new LinkedHashMap<>();
        security.put("allowCodeExecution", policy.isAllowCodeExecution());
        security.put("requireSandbox", policy.isRequireSandbox());
        security.put("allowNetworkAccess", policy.isAllowNetworkAccess());
        security.put("allowedLanguages", policy.getAllowedLanguages());
        security.put("allowedTools", policy.getAllowedTools());
        security.put("deniedTools", policy.getDeniedTools());
        
        ctx.json(security);
    }
    
    private void updateTenantSecurity(Context ctx) {
        String tenantId = ctx.pathParam("tenantId");
        TenantContext tenant = tenantManager.getTenant(tenantId);
        
        if (tenant == null) {
            ctx.status(404).json(Map.of("error", "Tenant not found: " + tenantId));
            return;
        }
        
        JSONObject body = JSON.parseObject(ctx.body());
        TenantSecurityPolicy policy = tenant.getSecurityPolicy();
        
        if (body.containsKey("allowCodeExecution")) {
            policy.setAllowCodeExecution(body.getBoolean("allowCodeExecution"));
        }
        if (body.containsKey("requireSandbox")) {
            policy.setRequireSandbox(body.getBoolean("requireSandbox"));
        }
        if (body.containsKey("allowNetworkAccess")) {
            policy.setAllowNetworkAccess(body.getBoolean("allowNetworkAccess"));
        }
        
        tenant.setSecurityPolicy(policy);
        
        try {
            java.nio.file.Path configDir = tenant.getTenantDir().resolve("config");
            policy.save(configDir);
        } catch (Exception e) {
            logger.warn("Failed to save security policy: {}", e.getMessage());
        }
        
        ctx.json(Map.of("success", true));
    }
    
    private void getTenantAuditLogs(Context ctx) {
        String tenantId = ctx.pathParam("tenantId");
        int limit = ctx.queryParamAsClass("limit", Integer.class).getOrDefault(100);
        
        TenantContext tenant = tenantManager.getTenant(tenantId);
        
        if (tenant == null) {
            ctx.status(404).json(Map.of("error", "Tenant not found: " + tenantId));
            return;
        }
        
        java.util.List<Map<String, Object>> events = tenant.getAuditLogger().getRecentEvents(limit).stream()
            .map(e -> {
                Map<String, Object> event = new LinkedHashMap<>();
                event.put("timestamp", e.timestamp().toString());
                event.put("type", e.event().name());
                event.put("details", e.details());
                return event;
            })
            .collect(java.util.stream.Collectors.toList());
        
        ctx.json(Map.of("events", events));
    }
    
    private void getTenantSessions(Context ctx) {
        String tenantId = ctx.pathParam("tenantId");
        TenantContext tenant = tenantManager.getTenant(tenantId);
        
        if (tenant == null) {
            ctx.status(404).json(Map.of("error", "Tenant not found: " + tenantId));
            return;
        }
        
        // Return session count (session IDs not available in current implementation)
        ctx.json(Map.of(
            "activeSessions", tenant.getActiveSessionCount()
        ));
    }
    
    private void getTenantSkills(Context ctx) {
        String tenantId = ctx.pathParam("tenantId");
        TenantContext tenant = tenantManager.getTenant(tenantId);
        
        if (tenant == null) {
            ctx.status(404).json(Map.of("error", "Tenant not found: " + tenantId));
            return;
        }
        
        List<String> skillNames = tenant.getSkillManager().listSkills().stream()
            .map(summary -> summary.name())
            .collect(java.util.stream.Collectors.toList());
        
        ctx.json(Map.of(
            "installedSkills", skillNames,
            "totalSkills", skillNames.size()
        ));
    }
}
