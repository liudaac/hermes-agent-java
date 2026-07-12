package com.nousresearch.hermes.dashboard;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.nousresearch.hermes.dashboard.handlers.*;
import com.nousresearch.hermes.dashboard.jarvis.ApprovalBridge;
import com.nousresearch.hermes.dashboard.jarvis.ChatService;
import com.nousresearch.hermes.dashboard.jarvis.IntentRouter;
import com.nousresearch.hermes.dashboard.jarvis.JarvisHandler;
import com.nousresearch.hermes.dashboard.jarvis.ProductQueryService;
import com.nousresearch.hermes.org.auth.PermissionPolicy;
import com.nousresearch.hermes.org.compliance.ComplianceFramework;
import com.nousresearch.hermes.org.distributed.AgentRegistry;
import com.nousresearch.hermes.org.evolution.SelfEvolutionEngine;
import com.nousresearch.hermes.org.handoff.HandoffProtocol;
import com.nousresearch.hermes.org.identity.AgentIdentityManager;
import com.nousresearch.hermes.org.knowledge.OrganizationalKnowledgeBase;
import com.nousresearch.hermes.org.market.AgentMarketplace;
import com.nousresearch.hermes.org.market.CostAttribution;
import com.nousresearch.hermes.org.observe.AgentObservability;
import com.nousresearch.hermes.org.workflow.WorkflowEngine;
import java.util.function.Supplier;
import java.time.Instant;
import java.util.Map;
import com.nousresearch.hermes.config.Constants;
import com.nousresearch.hermes.config.HermesConfig;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import com.nousresearch.hermes.tenant.core.TenantManager;
import com.nousresearch.hermes.workspace.WorkspaceDashboardIntegration;
import com.nousresearch.hermes.workspace.BusinessPortalDashboardIntegration;
import com.nousresearch.hermes.workspace.BusinessPortalExtendedIntegration;
import com.nousresearch.hermes.workspace.WorkspaceService;
import com.nousresearch.hermes.blueprint.QuickTeamBuilderDashboardIntegration;
import com.nousresearch.hermes.blueprint.QuickTeamBuilderService;
import com.nousresearch.hermes.blueprint.TeamBlueprintService;
import com.nousresearch.hermes.business.approval.BusinessApprovalDashboardIntegration;
import com.nousresearch.hermes.business.approval.BusinessApprovalService;
import com.nousresearch.hermes.business.run.BusinessRunDashboardIntegration;
import com.nousresearch.hermes.business.run.BusinessRunService;
import com.nousresearch.hermes.business.insight.BusinessInsightDashboardIntegration;
import com.nousresearch.hermes.business.insight.BusinessInsightService;
import com.nousresearch.hermes.scenario.PlanReflectionService;
import com.nousresearch.hermes.scenario.ScenarioDashboardIntegration;
import com.nousresearch.hermes.scenario.ScenarioService;
import com.nousresearch.hermes.prompt.PromptAssetDashboardIntegration;
import com.nousresearch.hermes.prompt.PromptAssetService;
import com.nousresearch.hermes.prompt.PromptAssetResolver;
import com.nousresearch.hermes.evolution.EvolutionProposalDashboardIntegration;
import com.nousresearch.hermes.evolution.EvolutionProposalService;
import com.nousresearch.hermes.memory.BusinessMemoryNoteService;
import com.nousresearch.hermes.policy.PolicyDashboardIntegration;
import com.nousresearch.hermes.policy.PolicyService;
import com.nousresearch.hermes.evalset.EvalSetService;
import com.nousresearch.hermes.canary.CanaryReleaseService;
import com.nousresearch.hermes.business.foundation.BusinessPortalAdapterRegistry;
import com.nousresearch.hermes.business.foundation.BusinessPortalFoundationFacade;
import io.javalin.Javalin;
import io.javalin.config.JavalinConfig;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.Map;

/**
 * Hermes Dashboard Web Server.
 * Provides REST API for Web Dashboard and TUI, with session management,
 * configuration, and static asset serving.
 */
public class DashboardServer {
    private static final Logger logger = LoggerFactory.getLogger(DashboardServer.class);

    // ---- 服务器配置 ----
    private final int port;
    private final String host;
    private final HermesConfig config;
    private Javalin app;
    private volatile boolean running = false;
    /** 服务器启动时间戳 — 用于计算 uptime */
    private final long startTime = System.currentTimeMillis();

    // ---- 安全相关 ----
    /** 会话令牌 — 生成后不可变，用于 /api/ 路由的 Bearer 鉴权 */
    private final String sessionToken;
    /** SSE 短期签名密钥（启动随机生成，不持久化，重启即失效）*/
    private final byte[] sseSigningKey;
    /** SSE token 有效期（秒）*/
    private static final long SSE_TOKEN_TTL_SECONDS = 600; // 10 minutes
    /** 公开 API 路径白名单 — 无需鉴权 */
    private final Set<String> publicApiPaths = Set.of(
        "/api/status",
        "/api/config/defaults",
        "/api/config/schema",
        "/api/model/info",
        "/api/dashboard/themes",
        "/api/dashboard/plugins",
        "/api/dashboard/plugins/rescan"
    );
    /** 本地回环地址集合 — 用于 Host 头校验 */
    private final Set<String> loopbackHosts = Set.of("localhost", "127.0.0.1", "::1");

    // ---- reveal 接口限流 ----
    private final ConcurrentHashMap<String, Long> revealTimestamps = new ConcurrentHashMap<>();
    private static final int REVEAL_MAX_PER_WINDOW = 5;
    private static final long REVEAL_WINDOW_SECONDS = 30;

    // ---- Dashboard Handler ----
    private final ConfigHandler configHandler;
    private final SessionHandler sessionHandler;
    private final EnvHandler envHandler;
    private final LogsHandler logsHandler;
    private final SkillsHandler skillsHandler;
    private final ToolsHandler toolsHandler;
    private final GatewayHandler gatewayHandler;
    private CronHandler cronHandler;
    private final OAuthProvidersHandler oauthProvidersHandler;
    private final AnalyticsHandler analyticsHandler;
    private final OrgControlCenterHandler orgControlCenterHandler;
    private final JarvisHandler jarvisHandler;
    /** AI 原生组织 API 统一处理器 — 聚合 identity、handoff、auth 等 12 个模块 */
    private final OrgApiHandler orgApiHandler = new OrgApiHandler()
            .with("identity", new AgentIdentityManager())
            .with("handoff", new HandoffProtocol())
            .with("auth", new PermissionPolicy())
            .with("knowledge", new OrganizationalKnowledgeBase())
            .with("workflow", new WorkflowEngine(java.nio.file.Paths.get(System.getProperty("user.home"), ".hermes", "workflows")))
            .with("market", new AgentMarketplace())
            .with("cost", new CostAttribution())
            .with("observe", new AgentObservability())
            .with("distributed", new AgentRegistry())
            .with("evolution", new SelfEvolutionEngine())
            .with("compliance", new ComplianceFramework());

    // ---- Business Portal 核心服务 ----
    private final TenantManager tenantManager;
    private final WorkspaceService workspaceService;
    private final TeamBlueprintService teamBlueprintService;
    private final BusinessApprovalService businessApprovalService;
    private final BusinessRunService businessRunService;
    /** 工具级审批协调器 — 在 Agent 工具调用前检查审批规则 */
    private com.nousresearch.hermes.business.approval.ToolApprovalCoordinator toolApprovalCoordinator;
    private final QuickTeamBuilderService quickTeamBuilderService;
    private final BusinessInsightService businessInsightService;
    private final ScenarioService scenarioService;
    private final PromptAssetService promptAssetService;
    private final EvolutionProposalService evolutionProposalService;
    private final PolicyService policyService;
    private final com.nousresearch.hermes.business.template.BusinessTemplateService businessTemplateService;
    private final com.nousresearch.hermes.business.template.TemplateCloneService templateCloneService;
    private final com.nousresearch.hermes.evalset.EvalSetService evalSetService;
    private final com.nousresearch.hermes.canary.CanaryReleaseService canaryReleaseService;
    private final com.nousresearch.hermes.memory.BusinessMemoryNoteService activeMemoryService;
    private final BusinessPortalFoundationFacade businessPortalFoundationFacade;

    // ---- 扩展编排服务 ----
    private final com.nousresearch.hermes.business.sla.SLAManager slaManager;
    private final com.nousresearch.hermes.business.dlq.DeadLetterQueue deadLetterQueue;
    private final com.nousresearch.hermes.business.analytics.ApprovalAnalytics approvalAnalytics;
    private final com.nousresearch.hermes.business.humanintheloop.HumanOverrideService humanOverrideService;
    private final com.nousresearch.hermes.business.workflow.BusinessWorkflowService workflowService;
    private final com.nousresearch.hermes.connector.ConnectorRegistry connectorRegistry;
    private final com.nousresearch.hermes.business.vertical.ecommerce.EcommerceScenarioFactory ecommerceScenarioFactory;
    private final com.nousresearch.hermes.business.event.BusinessEventBus businessEventBus;
    private final com.nousresearch.hermes.tenant.metrics.MetricsCollector metricsCollector;

    // ---- 外部状态供应 ----
    private final Supplier<GatewayRuntimeStatus> gatewayStatusSupplier;
    /** 组织统计供应器 — 由外部注入，用于 OrgOverviewHandler */
    private Supplier<Map<String, Object>> orgStatsSupplier;

    /**
     * 简化构造函数 — 使用默认 TenantManager 和断开状态的 Gateway 供应器。
     *
     * @param port   监听端口
     * @param host   绑定主机
     * @param config Hermes 配置
     */
    public DashboardServer(int port, String host, HermesConfig config) {
        this(port, host, config, new TenantManager(), GatewayRuntimeStatus::disconnected);
    }

    /**
     * 指定 TenantManager 的构造函数。
     *
     * @param port          监听端口
     * @param host          绑定主机
     * @param config        Hermes 配置
     * @param tenantManager 租户管理器
     */
    public DashboardServer(int port, String host, HermesConfig config, TenantManager tenantManager) {
        this(port, host, config, tenantManager, GatewayRuntimeStatus::disconnected);
    }

    /**
     * 完整构造函数 — 初始化所有 Handler 和 Business Portal 服务。
     *
     * @param port                  监听端口
     * @param host                  绑定主机
     * @param config                Hermes 配置
     * @param tenantManager         租户管理器
     * @param gatewayStatusSupplier Gateway 状态供应器
     */
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
        this.sessionToken = loadOrCreateSessionToken();
        this.sseSigningKey = generateSigningKey();

        // ── Plain HTTP handlers (no business deps) ──────────
        this.configHandler = new ConfigHandler(config);
        this.sessionHandler = new SessionHandler();
        this.orgControlCenterHandler = new OrgControlCenterHandler(tenantManager);
        this.envHandler = new EnvHandler();
        this.logsHandler = new LogsHandler();
        this.skillsHandler = new SkillsHandler();
        this.toolsHandler = new ToolsHandler();
        this.gatewayHandler = new GatewayHandler();
        this.oauthProvidersHandler = new OAuthProvidersHandler();
        this.analyticsHandler = new AnalyticsHandler();

        // ── Business service graph (extracted to BusinessServices
        //    to keep this constructor under ~50 lines).
        BusinessServices svc = BusinessServices.build(config, tenantManager);
        this.workspaceService            = svc.workspaceService;
        this.teamBlueprintService        = svc.teamBlueprintService;
        this.scenarioService             = svc.scenarioService;
        this.businessApprovalService     = svc.businessApprovalService;
        this.businessRunService          = svc.businessRunService;
        this.businessInsightService      = svc.businessInsightService;
        this.promptAssetService          = svc.promptAssetService;
        this.evolutionProposalService    = svc.evolutionProposalService;
        this.policyService               = svc.policyService;
        this.businessTemplateService     = svc.businessTemplateService;
        this.templateCloneService        = svc.templateCloneService;
        this.evalSetService              = svc.evalSetService;
        this.canaryReleaseService        = svc.canaryReleaseService;
        this.activeMemoryService         = svc.activeMemoryService;
        this.businessPortalFoundationFacade = svc.businessPortalFoundationFacade;
        this.slaManager                  = svc.slaManager;
        this.deadLetterQueue             = svc.deadLetterQueue;
        this.approvalAnalytics           = svc.approvalAnalytics;
        this.humanOverrideService        = svc.humanOverrideService;
        this.workflowService             = svc.workflowService;
        this.connectorRegistry           = svc.connectorRegistry;
        this.ecommerceScenarioFactory    = svc.ecommerceScenarioFactory;
        this.businessEventBus            = svc.businessEventBus;
        this.toolApprovalCoordinator     = svc.toolApprovalCoordinator;
        this.quickTeamBuilderService     = svc.quickTeamBuilderService;
        this.jarvisHandler               = svc.jarvisHandler;
        this.metricsCollector            = svc.metricsCollector;

        // ── Cron runs against workspace agents ──────────────
        this.cronHandler = new CronHandler(
            Path.of(System.getProperty("user.home"), ".hermes", "dashboard-cron-jobs.json"),
            true,
            new com.nousresearch.hermes.dashboard.handlers.AgentCronRunner(config, tenantManager, workspaceService)
        );

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

    /**
     * Load a persisted session token from disk, or generate a new one and persist it.
     * This allows restarts to keep existing browser sessions authenticated (the token
     * injected into window.__HERMES_SESSION_TOKEN__ stays stable). The file is mode 600
     * so only the running user can read it.
     */
    private String loadOrCreateSessionToken() {
        try {
            Path hermesHome = Constants.getHermesHome();
            Path stateDir = hermesHome.resolve("state");
            Files.createDirectories(stateDir);
            Path tokenFile = stateDir.resolve("session-token");
            if (Files.exists(tokenFile)) {
                String existing = Files.readString(tokenFile, StandardCharsets.UTF_8).trim();
                if (existing.length() >= 32) return existing;
            }
            String newToken = generateSessionToken();
            Files.writeString(tokenFile, newToken, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                // best-effort: tighten permissions to owner-only
                java.nio.file.attribute.PosixFilePermission ownerRead =
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ;
                java.nio.file.attribute.PosixFilePermission ownerWrite =
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE;
                Files.setPosixFilePermissions(tokenFile, java.util.Set.of(ownerRead, ownerWrite));
            } catch (UnsupportedOperationException | SecurityException ignored) {
                // Windows / non-POSIX FS — skip; file permissions are best-effort
            }
            return newToken;
        } catch (Exception e) {
            logger.warn("Failed to persist session token, falling back to ephemeral token: {}", e.getMessage());
            return generateSessionToken();
        }
    }

    private byte[] generateSigningKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }

    /**
     * Issue a short-lived signed token for SSE streams.
     * Format: base64url(expiry).base64url(hmac)
     * Expiry is seconds since epoch; TTL = SSE_TOKEN_TTL_SECONDS (10 min).
     * This is the legacy admin-scope token (equivalent to allAccess=true).
     */
    String issueSseToken() {
        return issueSseToken(null, true);
    }

    /**
     * Issue a scoped SSE token. The payload is signed and carries:
     *   expiry|workspaceId|allAccess
     * where workspaceId may be empty (for allAccess=true tokens) and
     * allAccess is "1" (true) or "0" (false). The server no longer trusts
     * ?workspaceId= / ?all= query params when a signed scoped token is
     * presented; the scope is decoded from the token itself.
     */
    String issueSseToken(String workspaceId, boolean allAccess) {
        try {
            long expiry = System.currentTimeMillis() / 1000L + SSE_TOKEN_TTL_SECONDS;
            String ws = (workspaceId == null) ? "" : workspaceId;
            String all = allAccess ? "1" : "0";
            // payload: <expiry>|<workspace>|<all>
            String payload = Long.toUnsignedString(expiry) + "|" + ws + "|" + all;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(sseSigningKey, "HmacSHA256"));
            byte[] sig = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            String payloadB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
            String sigB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(sig);
            return payloadB64 + "." + sigB64;
        } catch (Exception e) {
            throw new RuntimeException("Failed to issue SSE token", e);
        }
    }

    /** Parsed scope from a signed SSE token. */
    public record SseTokenScope(long expiry, String workspaceId, boolean allAccess, boolean legacy) {}

    /**
     * Validate an SSE query token and return its scope. Returns null if invalid.
     * Accepts three forms:
     *   1. New scoped signed token: payload contains expiry|ws|all (preferred)
     *   2. Legacy signed token: payload is only expiry (admin-equivalent)
     *   3. Permanent sessionToken string (legacy, logged as warning)
     */
    SseTokenScope validateSseToken(String token) {
        if (token == null || token.isBlank()) return null;
        int dot = token.indexOf('.');
        if (dot > 0 && dot < token.length() - 1) {
            try {
                String payloadB64 = token.substring(0, dot);
                String sigB64 = token.substring(dot + 1);
                byte[] payload = Base64.getUrlDecoder().decode(payloadB64);
                byte[] expected = Base64.getUrlDecoder().decode(sigB64);
                String payloadStr = new String(payload, StandardCharsets.UTF_8);
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(sseSigningKey, "HmacSHA256"));
                byte[] actual = mac.doFinal(payloadStr.getBytes(StandardCharsets.UTF_8));
                if (!MessageDigest.isEqual(expected, actual)) return null;

                long now = System.currentTimeMillis() / 1000L;
                // Split payload: expiry[|ws[|all]]
                String[] parts = payloadStr.split("\\|", -1);
                long expiry = Long.parseUnsignedLong(parts[0]);
                if (expiry < now) return null;
                if (parts.length >= 3) {
                    // New scoped form
                    String ws = parts[1].isEmpty() ? null : parts[1];
                    boolean all = "1".equals(parts[2]);
                    return new SseTokenScope(expiry, ws, all, false);
                } else {
                    // Legacy single-expiry form → admin scope
                    return new SseTokenScope(expiry, null, true, true);
                }
            } catch (Exception e) {
                logger.debug("SSE token parse failed: {}", e.getMessage());
            }
        }
        // Permanent-token fallback
        if (constantTimeEquals(token, sessionToken)) {
            logger.warn("SSE stream authenticated with permanent session token; migrate client to short-lived SSE token");
            return new SseTokenScope(Long.MAX_VALUE / 1000L, null, true, true);
        }
        return null;
    }

    /** Back-compat: simple validity check used by auth middleware. */
    boolean isValidSseToken(String token) {
        return validateSseToken(token) != null;
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        byte[] ba = a.getBytes(StandardCharsets.UTF_8);
        byte[] bb = b.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(ba, bb);
    }

    /** 获取当前会话令牌 — 用于前端鉴权和 SSE 连接参数。 */
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

        // Post-start recovery: sweep any runs left in RUNNING state by a
        // previous JVM that crashed before completing. Mark them FAILED so
        // the UI doesn't show them stuck "in progress" forever.
        try {
            int recovered = businessRunService.recoverOrphanedRuns();
            if (recovered > 0) {
                logger.info("Recovered {} orphaned RUNNING run(s) on startup", recovered);
            }
        } catch (Exception e) {
            logger.warn("Run recovery failed (non-fatal): {}", e.getMessage());
        }

        // Start periodic metrics collection + alert evaluation.
        try {
            metricsCollector.start();
        } catch (Exception e) {
            logger.warn("Failed to start metrics collector (non-fatal): {}", e.getMessage());
        }

        logger.info("Dashboard server started on http://{}:{}", host, port);
    }

    /**
     * Stop the dashboard server.
     */
    public void stop() {
        running = false;
        try {
            metricsCollector.stop();
        } catch (Exception ignored) {}
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
                ctx.skipRemainingHandlers();
                return;
            }

            // Auth middleware for /api/ routes
            String path = ctx.path();
            if (path.startsWith("/api/") && !publicApiPaths.contains(path) && !path.startsWith("/api/plugins/")) {
                String auth = ctx.header("Authorization");
                String expected = "Bearer " + sessionToken;

                // EventSource (SSE) cannot set custom headers — allow ?token= on SSE routes.
                // Token must be a short-lived signed SSE token (preferred) or the permanent
                // sessionToken (legacy, logged as warning).
                if ((auth == null || !constantTimeEquals(auth, expected))
                    && (path.equals("/api/logs/tail")
                        || path.equals("/api/jarvis/stream")
                        || path.matches("^/api/cron/jobs/[^/]+/runs/stream$"))) {
                    String tokenParam = ctx.queryParam("token");
                    if (isValidSseToken(tokenParam)) {
                        auth = expected;
                    }
                }

                if (auth == null || !constantTimeEquals(auth, expected)) {
                    ctx.status(401).result(JSON.toJSONString(new JSONObject()
                        .fluentPut("detail", "Unauthorized")));
                    ctx.skipRemainingHandlers();
                    return;
                }
            }
        });
    }

    /**
     * @deprecated Use {@link #constantTimeEquals(String, String)} for simple equality.
     * Kept for backward compatibility with any external callers.
     */
    @Deprecated
    private boolean hmacCompare(String a, String b) {
        return constantTimeEquals(a, b);
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
        app.get("/health", ctx -> {
            Map<String, Object> health = new LinkedHashMap<>();
            health.put("status", "UP");
            health.put("version", "0.1.0-SNAPSHOT");
            health.put("timestamp", Instant.now().toString());
            health.put("uptimeMs", System.currentTimeMillis() - startTime);
            try {
                health.put("tenants", tenantManager.listRegisteredTenants().size());
            } catch (Exception e) {
                health.put("tenants", -1);
            }
            ctx.status(200).json(health);
        });

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
        app.delete("/api/logs", logsHandler::deleteLog);
        app.get("/api/logs/aggregate", logsHandler::getAggregate);
        app.sse("/api/logs/tail", logsHandler::tail);

        // Skills API
        app.get("/api/skills", skillsHandler::getSkills);
        app.put("/api/skills/toggle", skillsHandler::toggleSkill);

        // Learning Graph API — /journey data for dashboard
        app.get("/api/learning/graph", ctx -> {
            try {
                var sm = new com.nousresearch.hermes.skills.SkillManager();
                var graph = new com.nousresearch.hermes.skills.LearningGraphService();
                graph.buildFromSkillManager(sm);

                var skillNodes = graph.getSkillNodes().stream()
                    .map(n -> {
                        var info = new com.nousresearch.hermes.skills.LearningGraphRenderer.GraphNodeInfo();
                        info.id = n.id();
                        info.label = n.label();
                        info.isMemory = false;
                        info.category = n.tags() != null && !n.tags().isEmpty() ? n.tags().get(0) : "skill";
                        info.meta = n.content() != null ? n.content() : "";
                        info.timestamp = java.time.Instant.now();
                        return info;
                    })
                    .toList();
                var frame = com.nousresearch.hermes.skills.LearningGraphRenderer.renderFrame(
                    skillNodes, java.util.List.of(), 80, 16);
                var payload = graph.toPayload();
                payload.put("frame", frame);
                ctx.json(payload);
            } catch (Exception e) {
                ctx.status(500).json(Map.of("error", e.getMessage()));
            }
        });

        // Learning Graph node mutations — inspect/delete/edit/pin
        app.get("/api/learning/node/{id}", ctx -> {
            var sm = new com.nousresearch.hermes.skills.SkillManager();
            var mm = new com.nousresearch.hermes.memory.MemoryManager();
            var mutations = new com.nousresearch.hermes.skills.LearningGraphMutations(sm, mm);
            String nodeId = ctx.pathParam("id");
            ctx.json(mutations.nodeDetail(nodeId));
        });
        app.delete("/api/learning/node/{id}", ctx -> {
            var sm = new com.nousresearch.hermes.skills.SkillManager();
            var mm = new com.nousresearch.hermes.memory.MemoryManager();
            var mutations = new com.nousresearch.hermes.skills.LearningGraphMutations(sm, mm);
            String nodeId = ctx.pathParam("id");
            ctx.json(mutations.deleteNode(nodeId));
        });
        app.put("/api/learning/node/{id}", ctx -> {
            var sm = new com.nousresearch.hermes.skills.SkillManager();
            var mm = new com.nousresearch.hermes.memory.MemoryManager();
            var mutations = new com.nousresearch.hermes.skills.LearningGraphMutations(sm, mm);
            String nodeId = ctx.pathParam("id");
            String content = ctx.body();
            ctx.json(mutations.editNode(nodeId, content));
        });
        app.post("/api/learning/node/{id}/pin", ctx -> {
            var sm = new com.nousresearch.hermes.skills.SkillManager();
            var mutations = new com.nousresearch.hermes.skills.LearningGraphMutations(sm);
            String nodeId = ctx.pathParam("id");
            ctx.json(mutations.pinSkill(nodeId));
        });
        app.post("/api/learning/node/{id}/unpin", ctx -> {
            var sm = new com.nousresearch.hermes.skills.SkillManager();
            var mutations = new com.nousresearch.hermes.skills.LearningGraphMutations(sm);
            String nodeId = ctx.pathParam("id");
            ctx.json(mutations.unpinSkill(nodeId));
        });

        // Tools API
        app.get("/api/tools/toolsets", toolsHandler::getToolsets);
        app.get("/api/tools", toolsHandler::getTools);
        app.get("/api/tools/{name}", toolsHandler::getToolDetail);

        // Gateway control API
        app.post("/api/gateway/restart", gatewayHandler::restartGateway);
        app.post("/api/hermes/update", gatewayHandler::updateHermes);
        app.get("/api/actions/{name}/status", gatewayHandler::getActionStatus);

        // Analytics API
        app.get("/api/analytics/usage", analyticsHandler::getUsage);

        // Cron jobs API
        app.get("/api/cron/jobs", cronHandler::listJobs);
        app.post("/api/cron/jobs", cronHandler::createJob);
        app.post("/api/cron/jobs/{id}/pause", cronHandler::pauseJob);
        app.post("/api/cron/jobs/{id}/resume", cronHandler::resumeJob);
        app.post("/api/cron/jobs/{id}/trigger", cronHandler::triggerJob);
        app.delete("/api/cron/jobs/{id}", cronHandler::deleteJob);
        app.get("/api/cron/jobs/{id}/runs", cronHandler::getJobRuns);
        app.sse("/api/cron/jobs/{id}/runs/stream", cronHandler::streamJobRuns);
        app.get("/api/cron/preview", cronHandler::previewSchedule);

        // ========== Tenant Management APIs ==========
        TenantDashboardIntegration.registerRoutes(app, tenantManager);

        // ========== Business Portal APIs ==========
        WorkspaceDashboardIntegration.registerRoutes(app, workspaceService, teamBlueprintService);
        ScenarioDashboardIntegration.registerRoutes(app, scenarioService, businessRunService);
        PromptAssetDashboardIntegration.registerRoutes(app, promptAssetService);
        EvolutionProposalDashboardIntegration.registerRoutes(app, evolutionProposalService);
        BusinessApprovalDashboardIntegration.registerRoutes(app, businessApprovalService, scenarioService, businessRunService, toolApprovalCoordinator);
        BusinessRunDashboardIntegration.registerRoutes(app, businessRunService);
        BusinessInsightDashboardIntegration.registerRoutes(app, businessInsightService);
        PolicyDashboardIntegration.registerRoutes(app, policyService);
        BusinessPortalDashboardIntegration.registerRoutes(app, workspaceService, teamBlueprintService, businessApprovalService, businessRunService, businessInsightService);
        BusinessPortalExtendedIntegration.registerRoutes(app, workspaceService, slaManager, deadLetterQueue, approvalAnalytics, humanOverrideService, workflowService, connectorRegistry, ecommerceScenarioFactory);
        QuickTeamBuilderDashboardIntegration.registerRoutes(app, quickTeamBuilderService, teamBlueprintService);
        com.nousresearch.hermes.business.template.BusinessTemplateDashboardIntegration.registerRoutes(
            app, businessTemplateService, templateCloneService);
        com.nousresearch.hermes.business.template.BusinessRiskPolicyDashboardIntegration.registerRoutes(
            app, businessTemplateService);
        com.nousresearch.hermes.business.template.BusinessIndustryDashboardIntegration.registerRoutes(
            app, businessRunService, businessTemplateService);
        com.nousresearch.hermes.business.notification.BusinessApprovalNotifier approvalNotifier =
            new com.nousresearch.hermes.business.notification.BusinessApprovalNotifier(businessApprovalService);
        com.nousresearch.hermes.business.notification.BusinessApprovalNotifier.registerRoutes(app, approvalNotifier);
        com.nousresearch.hermes.evalset.EvalSetDashboardIntegration.registerRoutes(app, evalSetService);
        com.nousresearch.hermes.canary.CanaryReleaseDashboardIntegration.registerRoutes(app, canaryReleaseService);
        com.nousresearch.hermes.memory.BusinessMemoryNoteDashboardIntegration.registerRoutes(app, activeMemoryService);
        // Business orchestration real-time SSE stream
        var businessEventSseHandler = new com.nousresearch.hermes.workspace.BusinessEventSSEHandler(businessEventBus);
        app.sse("/api/v1/business/events/stream", businessEventSseHandler::handle);
        app.get("/api/v1/business/foundation/diagnostics", ctx -> ctx.status(200).json(Map.of(
            "ok", true,
            "diagnostics", businessPortalFoundationFacade.diagnostics().toMap()
        )));
        app.post("/api/v1/business/foundation/team-blueprints/validate", ctx -> {
            JSONObject body = ctx.body() == null || ctx.body().isBlank() ? new JSONObject() : JSON.parseObject(ctx.body());
            String workspaceId = body.getString("workspaceId");
            String teamId = body.getString("teamId");
            if (workspaceId == null || workspaceId.isBlank() || teamId == null || teamId.isBlank()) {
                ctx.status(400).json(Map.of(
                    "ok", false,
                    "error", "workspaceId and teamId are required"
                ));
                return;
            }
            try {
                var team = teamBlueprintService.requireTeamBlueprint(workspaceId, teamId);
                var report = businessPortalFoundationFacade.validateTeamBlueprint(workspaceId, team);
                ctx.status(200).json(Map.of(
                    "ok", true,
                    "workspaceId", workspaceId,
                    "teamId", teamId,
                    "validation", report.toMap()
                ));
            } catch (WorkspaceService.WorkspaceNotFoundException | com.nousresearch.hermes.blueprint.TeamBlueprintService.TeamBlueprintNotFoundException e) {
                ctx.status(404).json(Map.of(
                    "ok", false,
                    "error", e.getMessage(),
                    "workspaceId", workspaceId,
                    "teamId", teamId
                ));
            } catch (Exception e) {
                ctx.status(500).json(Map.of(
                    "ok", false,
                    "error", e.getMessage(),
                    "workspaceId", workspaceId,
                    "teamId", teamId
                ));
            }
        });
        app.post("/api/v1/business/foundation/prompt-context/preview", ctx -> {
            JSONObject body = ctx.body() == null || ctx.body().isBlank() ? new JSONObject() : JSON.parseObject(ctx.body());
            String workspaceId = body.getString("workspaceId");
            JSONArray refsArray = body.getJSONArray("promptAssetRefs");
            if (workspaceId == null || workspaceId.isBlank() || refsArray == null || refsArray.isEmpty()) {
                ctx.status(400).json(Map.of(
                    "ok", false,
                    "error", "workspaceId and promptAssetRefs are required"
                ));
                return;
            }
            java.util.List<String> refs = new java.util.ArrayList<>();
            for (Object item : refsArray) {
                if (item != null) refs.add(String.valueOf(item));
            }
            boolean includeFoundationContext = body.getBooleanValue("includeFoundationContext");
            PromptAssetResolver.ResolveOptions options = includeFoundationContext
                ? PromptAssetResolver.ResolveOptions.withFoundationContext()
                : PromptAssetResolver.ResolveOptions.promptOnly();
            try {
                var promptContext = businessPortalFoundationFacade.resolvePromptContext(
                    workspaceId,
                    refs,
                    body.getString("taskContext"),
                    options
                );
                ctx.status(200).json(Map.of(
                    "ok", true,
                    "workspaceId", workspaceId,
                    "promptContext", promptContext.toMap(),
                    "rendered", promptContext.render()
                ));
            } catch (WorkspaceService.WorkspaceNotFoundException e) {
                ctx.status(404).json(Map.of(
                    "ok", false,
                    "error", e.getMessage(),
                    "workspaceId", workspaceId
                ));
            } catch (Exception e) {
                ctx.status(500).json(Map.of(
                    "ok", false,
                    "error", e.getMessage(),
                    "workspaceId", workspaceId
                ));
            }
        });
        app.post("/api/v1/business/foundation/scenarios/plan", ctx -> {
            JSONObject body = ctx.body() == null || ctx.body().isBlank() ? new JSONObject() : JSON.parseObject(ctx.body());
            String workspaceId = body.getString("workspaceId");
            String scenarioId = body.getString("scenarioId");
            if (workspaceId == null || workspaceId.isBlank() || scenarioId == null || scenarioId.isBlank()) {
                ctx.status(400).json(Map.of(
                    "ok", false,
                    "error", "workspaceId and scenarioId are required"
                ));
                return;
            }
            try {
                var scenario = scenarioService.requireScenario(workspaceId, scenarioId);
                var request = businessPortalFoundationFacade.buildScenarioIntentRequest(scenario, body.getString("userInput"));
                var plan = businessPortalFoundationFacade.planScenarioIntent(scenario, body.getString("userInput"));
                ctx.status(200).json(Map.of(
                    "ok", true,
                    "workspaceId", workspaceId,
                    "scenarioId", scenarioId,
                    "intentRequest", request.toMap(),
                    "plan", plan.toMap()
                ));
            } catch (WorkspaceService.WorkspaceNotFoundException | ScenarioService.ScenarioNotFoundException e) {
                ctx.status(404).json(Map.of(
                    "ok", false,
                    "error", e.getMessage(),
                    "workspaceId", workspaceId,
                    "scenarioId", scenarioId
                ));
            } catch (Exception e) {
                ctx.status(500).json(Map.of(
                    "ok", false,
                    "error", e.getMessage(),
                    "workspaceId", workspaceId,
                    "scenarioId", scenarioId
                ));
            }
        });
        app.post("/api/v1/business/foundation/runs/project", ctx -> {
            JSONObject body = ctx.body() == null || ctx.body().isBlank() ? new JSONObject() : JSON.parseObject(ctx.body());
            String workspaceId = body.getString("workspaceId");
            String intentRunId = body.getString("intentRunId");
            if (workspaceId == null || workspaceId.isBlank() || intentRunId == null || intentRunId.isBlank()) {
                ctx.status(400).json(Map.of(
                    "ok", false,
                    "error", "workspaceId and intentRunId are required"
                ));
                return;
            }
            try {
                var workspace = workspaceService.requireWorkspace(workspaceId);
                var tenant = tenantManager.getTenant(workspace.getTenantId());
                if (tenant == null) {
                    ctx.status(404).json(Map.of(
                        "ok", false,
                        "error", "Tenant not found for workspace: " + workspaceId,
                        "workspaceId", workspaceId,
                        "intentRunId", intentRunId
                    ));
                    return;
                }
                var run = tenant.getScenarioOrchestrator().getRun(intentRunId);
                if (run == null) {
                    ctx.status(404).json(Map.of(
                        "ok", false,
                        "error", "Intent run not found: " + intentRunId,
                        "workspaceId", workspaceId,
                        "intentRunId", intentRunId
                    ));
                    return;
                }
                String scenarioId = body.getString("scenarioId");
                String scenarioName = body.getString("scenarioName");
                if (scenarioName == null || scenarioName.isBlank()) {
                    scenarioName = scenarioId != null && !scenarioId.isBlank() ? scenarioId : "Foundation intent run";
                }
                var projection = businessPortalFoundationFacade.projectIntentRun(workspaceId, scenarioId, scenarioName, run);
                ctx.status(200).json(Map.of(
                    "ok", true,
                    "workspaceId", workspaceId,
                    "intentRunId", intentRunId,
                    "projection", projection
                ));
            } catch (WorkspaceService.WorkspaceNotFoundException e) {
                ctx.status(404).json(Map.of(
                    "ok", false,
                    "error", e.getMessage(),
                    "workspaceId", workspaceId,
                    "intentRunId", intentRunId
                ));
            } catch (Exception e) {
                ctx.status(500).json(Map.of(
                    "ok", false,
                    "error", e.getMessage(),
                    "workspaceId", workspaceId,
                    "intentRunId", intentRunId
                ));
            }
        });
        app.post("/api/v1/business/foundation/insights/project", ctx -> {
            JSONObject body = ctx.body() == null || ctx.body().isBlank() ? new JSONObject() : JSON.parseObject(ctx.body());
            String workspaceId = body.getString("workspaceId");
            if (workspaceId == null || workspaceId.isBlank()) {
                ctx.status(400).json(Map.of(
                    "ok", false,
                    "error", "workspaceId is required"
                ));
                return;
            }
            try {
                var workspace = workspaceService.requireWorkspace(workspaceId);
                var tenant = tenantManager.getTenant(workspace.getTenantId());
                if (tenant == null) {
                    ctx.status(404).json(Map.of(
                        "ok", false,
                        "error", "Tenant not found for workspace: " + workspaceId,
                        "workspaceId", workspaceId
                    ));
                    return;
                }
                int limit = body.getIntValue("limit");
                if (limit <= 0) limit = 50;
                if (limit > 200) limit = 200;
                var traces = tenant.getObservability().getAllRecentTraces(limit);
                var evolutionSummary = tenant.getEvolutionEngine().getSummary();
                var summary = businessPortalFoundationFacade.projectFoundationInsights(
                    workspaceId,
                    traces,
                    java.util.List.of(),
                    evolutionSummary
                );
                ctx.status(200).json(Map.of(
                    "ok", true,
                    "workspaceId", workspaceId,
                    "traceCount", traces.size(),
                    "summary", summary
                ));
            } catch (WorkspaceService.WorkspaceNotFoundException e) {
                ctx.status(404).json(Map.of(
                    "ok", false,
                    "error", e.getMessage(),
                    "workspaceId", workspaceId
                ));
            } catch (Exception e) {
                ctx.status(500).json(Map.of(
                    "ok", false,
                    "error", e.getMessage(),
                    "workspaceId", workspaceId
                ));
            }
        });
        app.post("/api/v1/business/foundation/evolution-proposals/preview", ctx -> {
            JSONObject body = ctx.body() == null || ctx.body().isBlank() ? new JSONObject() : JSON.parseObject(ctx.body());
            String workspaceId = body.getString("workspaceId");
            String proposalId = body.getString("proposalId");
            if (workspaceId == null || workspaceId.isBlank() || proposalId == null || proposalId.isBlank()) {
                ctx.status(400).json(Map.of(
                    "ok", false,
                    "error", "workspaceId and proposalId are required"
                ));
                return;
            }
            try {
                var proposal = evolutionProposalService.requireProposal(workspaceId, proposalId);
                var failureCase = businessPortalFoundationFacade.projectProposalFailureCase(proposal);
                var approvalCard = businessPortalFoundationFacade.projectProposalApproval(proposal);
                var delegatedEnvelope = businessPortalFoundationFacade.projectProposalDelegatedTaskEnvelope(proposal);
                ctx.status(200).json(Map.of(
                    "ok", true,
                    "workspaceId", workspaceId,
                    "proposalId", proposalId,
                    "failureCase", failureCase.toMap(),
                    "approvalCard", approvalCard,
                    "delegatedTaskEnvelope", delegatedEnvelope.toMap()
                ));
            } catch (WorkspaceService.WorkspaceNotFoundException | com.nousresearch.hermes.evolution.EvolutionProposalService.EvolutionProposalNotFoundException e) {
                ctx.status(404).json(Map.of(
                    "ok", false,
                    "error", e.getMessage(),
                    "workspaceId", workspaceId,
                    "proposalId", proposalId
                ));
            } catch (Exception e) {
                ctx.status(500).json(Map.of(
                    "ok", false,
                    "error", e.getMessage(),
                    "workspaceId", workspaceId,
                    "proposalId", proposalId
                ));
            }
        });

        // Legacy /api/organization/* routes removed in Sprint 4a; superseded by
        // /api/org/summary and /api/org/control/* endpoints. The OrgOverviewHandler
        // class is retained for backward-compat programmatic use.

        // --- AI-Native Org API (P0-P3 modules) ---
        app.get("/api/org/summary", orgApiHandler::fullOrgSummary);
        app.get("/api/org/identity", orgApiHandler::identitySummary);
        app.get("/api/org/identity/list", orgApiHandler::identityList);
        app.get("/api/org/identity/warnings", orgApiHandler::identityWarnings);
        app.get("/api/org/handoff", orgApiHandler::handoffSummary);
        app.get("/api/org/handoff/pending", orgApiHandler::handoffPending);
        app.get("/api/org/auth", orgApiHandler::authSummary);
        app.get("/api/org/auth/subjects", orgApiHandler::authSubjects);
        app.get("/api/org/knowledge", orgApiHandler::knowledgeSummary);
        app.get("/api/org/knowledge/search", orgApiHandler::knowledgeSearch);
        app.get("/api/org/workflow", orgApiHandler::workflowSummary);
        app.get("/api/org/workflow/list", orgApiHandler::workflowList);
        app.get("/api/org/workflow/waiting", orgApiHandler::workflowWaiting);
        app.get("/api/org/market", orgApiHandler::marketSummary);
        app.get("/api/org/market/templates", orgApiHandler::marketTemplates);
        app.get("/api/org/cost", orgApiHandler::costSummary);
        app.get("/api/org/observe", orgApiHandler::observeSummary);
        app.get("/api/org/observe/anomalies", orgApiHandler::observeAnomalies);
        app.get("/api/org/distributed", orgApiHandler::distributedSummary);
        app.get("/api/org/distributed/nodes", orgApiHandler::distributedNodes);
        app.get("/api/org/evolution", orgApiHandler::evolutionSummary);
        app.get("/api/org/evolution/failures", orgApiHandler::evolutionFailures);
        app.get("/api/org/evolution/patterns", orgApiHandler::evolutionPatterns);
        app.get("/api/org/compliance", orgApiHandler::complianceSummary);

        // --- Org Control Center API (五刀可视化聚合) ---

        app.get("/api/org/control/overview", orgControlCenterHandler::overview);
        app.get("/api/org/control/teams", orgControlCenterHandler::teams);
        app.get("/api/org/control/intents", orgControlCenterHandler::intents);
        app.get("/api/org/control/delegated-tasks", orgControlCenterHandler::delegatedTasks);
        app.post("/api/org/control/delegated-tasks/{tenantId}/{taskId}/submit", orgControlCenterHandler::submitDelegatedTask);
        app.post("/api/org/control/delegated-tasks/{tenantId}/{taskId}/verify", orgControlCenterHandler::verifyDelegatedTask);
        app.post("/api/org/control/delegated-tasks/{tenantId}/{taskId}/execute", orgControlCenterHandler::executeDelegatedTask);
        app.post("/api/org/control/intents/{tenantId}/{runId}/replay", orgControlCenterHandler::replayIntent);
        app.post("/api/org/control/intents/{tenantId}/{runId}/reroute", orgControlCenterHandler::rerouteIntent);
        app.post("/api/org/control/agents/{tenantId}/{agentId}/override", orgControlCenterHandler::agentOverride);
        app.get("/api/org/control/traces", orgControlCenterHandler::traces);
        // S3-3: 单条 trace 详情 endpoint（全链路可回放）
        app.get("/api/traces/{traceId}", ctx -> {
            String traceId = ctx.pathParam("traceId");
            // 遍历所有租户查找 trace
            for (var tenantEntry : tenantManager.getAllTenants().entrySet()) {
                var opt = tenantEntry.getValue().getObservability().getTrace(traceId);
                if (opt.isPresent()) {
                    var trace = opt.get();
                    java.util.Map<String, Object> resp = new java.util.LinkedHashMap<>();
                    resp.put("ok", true);
                    resp.put("traceId", trace.getTraceId());
                    resp.put("agentId", trace.getAgentId());
                    resp.put("sessionId", trace.getSessionId());
                    resp.put("task", trace.getTaskDescription() != null ? trace.getTaskDescription() : "");
                    resp.put("status", trace.getStatus() != null ? trace.getStatus().toString() : "UNKNOWN");
                    resp.put("startTime", trace.getStartTime() != null ? trace.getStartTime().toString() : "");
                    resp.put("endTime", trace.getEndTime() != null ? trace.getEndTime().toString() : "");
                    resp.put("totalTokens", trace.getTotalTokens());
                    resp.put("estimatedCost", trace.getEstimatedCost());
                    resp.put("errorCount", trace.getErrorCount());
                    resp.put("timeline", trace.toTimeline());
                    resp.put("steps", trace.getSteps() != null
                        ? trace.getSteps().stream().map(s -> {
                            java.util.Map<String, Object> stepMap = new java.util.LinkedHashMap<>();
                            stepMap.put("type", s.type() != null ? s.type().toString() : "UNKNOWN");
                            stepMap.put("content", s.content() != null ? s.content() : "");
                            stepMap.put("tokens", s.tokens());
                            stepMap.put("durationMs", s.durationMs());
                            stepMap.put("toolUsed", s.toolName() != null ? s.toolName() : "");
                            stepMap.put("confidence", s.confidence());
                            return stepMap;
                        }).toList()
                        : java.util.List.of()
                    );
                    ctx.status(200).json(resp);
                    return;
                }
            }
            ctx.status(404).json(java.util.Map.of("ok", false, "error", "Trace not found: " + traceId));
        });
        app.get("/api/org/control/evolution", orgControlCenterHandler::evolution);
        app.get("/api/org/control/anomalies", orgControlCenterHandler::anomalies);
        app.get("/api/org/control/audit", orgControlCenterHandler::audit);
        app.get("/api/org/control/browser", orgControlCenterHandler::browserTimeline);
        app.get("/api/org/control/browser/status", orgControlCenterHandler::browserStatus);
        app.get("/api/org/control/browser/{tenantId}/config", orgControlCenterHandler::browserBridgeConfig);
        app.post("/api/org/control/browser/{tenantId}/reset", orgControlCenterHandler::resetBrowserBridge);
        app.post("/api/org/control/browser/{tenantId}/clear-config", orgControlCenterHandler::clearBrowserBridgeConfig);
        app.post("/api/org/control/browser/{tenantId}/provider", orgControlCenterHandler::configureBrowserProvider);
        app.post("/api/org/control/browser/{tenantId}/health", orgControlCenterHandler::browserHealth);
        app.post("/api/org/control/browser/{tenantId}/capabilities", orgControlCenterHandler::browserCapabilities);
        app.post("/api/org/control/browser/{tenantId}/action", orgControlCenterHandler::browserDiagnosticAction);
        app.post("/api/org/control/browser/{tenantId}/contract", orgControlCenterHandler::browserContractTest);
        app.post("/api/org/control/browser/{tenantId}/probe", orgControlCenterHandler::browserProviderProbe);
        app.post("/api/org/control/browser/{tenantId}/probe/apply", orgControlCenterHandler::applyBrowserProbeRecommendation);
        app.get("/api/org/control/browser/approvals", orgControlCenterHandler::browserApprovals);
        app.post("/api/org/control/browser/approvals/{tenantId}/{approvalId}/approve", orgControlCenterHandler::approveBrowserApproval);
        app.post("/api/org/control/browser/approvals/{tenantId}/{approvalId}/reject", orgControlCenterHandler::rejectBrowserApproval);

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
        app.post("/api/jarvis/chat", jarvisHandler::chat);
        app.post("/api/jarvis/intent", jarvisHandler::classifyIntent);
        // Short-lived SSE token endpoint — Bearer-auth'd clients call this to refresh
        // the ?token= used for EventSource connections. The token injected into
        // window.__HERMES_SSE_TOKEN__ at page load expires in 10 min; long-lived pages
        // can call this endpoint every 9 minutes to roll.
        //
        // Clients may request a scoped token via ?workspaceId=xxx (portal scoped view)
        // or ?all=true (admin cross-workspace view for ops/noc). If neither is passed
        // the token defaults to admin scope (back-compat).
        app.get("/api/auth/sse-token", ctx -> {
            String requestedWs = ctx.queryParam("workspaceId");
            boolean requestedAll = "true".equalsIgnoreCase(ctx.queryParam("all"));
            // Validate workspaceId: 2-64 chars, safe character class only
            // (same rule as WorkspaceService.createWorkspace).
            String safeWs = null;
            if (requestedWs != null && !requestedWs.isBlank()) {
                if (!requestedWs.matches("[a-zA-Z0-9._-]{2,64}")) {
                    ctx.status(400).json(new JSONObject().fluentPut("error", "invalid workspaceId"));
                    return;
                }
                safeWs = requestedWs;
            }
            // If a workspace scope is requested, issue a workspace-scoped token
            // (no allAccess). Otherwise issue admin-scope token.
            String token;
            if (safeWs != null && !requestedAll) {
                token = issueSseToken(safeWs, false);
            } else {
                token = issueSseToken(null, true);
            }
            ctx.contentType("application/json");
            ctx.result(JSON.toJSONString(new JSONObject()
                .fluentPut("token", token)
                .fluentPut("expires_in", SSE_TOKEN_TTL_SECONDS)
                .fluentPut("workspaceId", safeWs == null ? "" : safeWs)
                .fluentPut("all", safeWs == null)));
        });
        // SSE can't set custom headers, so we only read ?token= from query params.
        // workspaceId/allAccess are DECODED FROM THE SIGNED TOKEN, not from URL —
        // this prevents a malicious client from forging scope by tweaking the URL.
        app.get("/api/jarvis/stream", ctx -> {
            String tokenParam = ctx.queryParam("token");
            SseTokenScope scope = validateSseToken(tokenParam);
            if (scope == null) {
                ctx.status(401).json(new JSONObject().fluentPut("error", "invalid or missing SSE token"));
                return;
            }
            final String effectiveWorkspaceId = scope.workspaceId();
            final boolean effectiveAll = scope.allAccess();
            io.javalin.http.sse.SseHandler sseHandler = new io.javalin.http.sse.SseHandler(
                client -> jarvisHandler.streamSuggestions(client, effectiveWorkspaceId, effectiveAll));
            sseHandler.handle(ctx);
        });
        app.post("/api/jarvis/approval/{approvalId}", jarvisHandler::resolveApproval);
        app.get("/api/dashboard/plugins/rescan", ctx -> ctx.json(new JSONObject()
            .fluentPut("ok", true)
            .fluentPut("count", 0)));

        // Prometheus-compatible metrics scrape endpoint.
        //
        // Authenticated with the same Bearer token as other /api/ routes
        // (or with a short-lived SSE token for pull-based scrapers that can't
        // set custom headers). Response is text/plain in Prometheus 0.0.4
        // exposition format.
        app.get("/metrics", ctx -> {
            ctx.contentType("text/plain; version=0.0.4; charset=utf-8");
            try {
                String out = metricsCollector.exportPrometheusMetrics();
                ctx.result(out);
            } catch (Exception e) {
                logger.warn("Failed to export metrics: {}", e.getMessage());
                ctx.status(500).result("# error exporting metrics");
            }
        });
    }

    /**
     * Register static asset routes.
     */
    private void registerStaticRoutes() {
        // Serve static assets from the configured or detected dashboard build directory.
        java.nio.file.Path webDist = resolveWebDistPath();

        if (java.nio.file.Files.exists(webDist)) {
            logger.info("Serving dashboard static assets from {}", webDist);
            // Root-hub static files. The hub build copies /assets, /fonts,
            // /ds-assets to web_dist/* via vite's "public" convention.
            app.get("/assets/*", ctx -> serveStaticFile(ctx, webDist.resolve("assets")));
            app.get("/fonts/*", ctx -> serveStaticFile(ctx, webDist.resolve("fonts")));
            app.get("/ds-assets/*", ctx -> serveStaticFile(ctx, webDist.resolve("ds-assets")));

            // Per-SPA static files. Each independent SPA's vite build has
            // base: '/<spa>/' which prefixes its built index.html to
            // reference /<spa>/assets/*. Those files live at
            // web_dist/<spa>/assets/. The /<spa>/favicon.ico and
            // /<spa>/manifest.webmanifest paths are served from the
            // shared webDist root so we don't need to copy them per SPA.
            //
            // serveStaticFile strips exactly one path segment (the SPA
            // prefix itself) and then resolves the rest under basePath.
            // So basePath must be the SPA's directory, not the deeper
            // /assets subdirectory — otherwise we'd end up with the
            // double "assets/assets" segment and a 404.
            for (String spa : new String[]{"portal", "ops", "noc"}) {
                String prefix = "/" + spa;
                java.nio.file.Path spaDir = webDist.resolve(spa);
                app.get(prefix + "/assets/*",
                    ctx -> serveStaticFile(ctx, spaDir));
                app.get(prefix + "/fonts/*",
                    ctx -> serveStaticFile(ctx, spaDir));
                app.get(prefix + "/ds-assets/*",
                    ctx -> serveStaticFile(ctx, spaDir));
                // SPA-scoped fallbacks for the shared root files.
                app.get(prefix + "/favicon.ico",
                    ctx -> serveSharedFile(ctx, webDist, "favicon.ico"));
                app.get(prefix + "/favicon.svg",
                    ctx -> serveSharedFile(ctx, webDist, "favicon.svg"));
                app.get(prefix + "/manifest.webmanifest",
                    ctx -> serveSharedFile(ctx, webDist, "manifest.webmanifest"));
            }

            // Shared root-level static files (favicon, manifest, sw).
            app.get("/favicon.ico", ctx -> serveSharedFile(ctx, webDist, "favicon.ico"));
            app.get("/favicon.svg", ctx -> serveSharedFile(ctx, webDist, "favicon.svg"));
            app.get("/manifest.webmanifest", ctx -> serveSharedFile(ctx, webDist, "manifest.webmanifest"));
            app.get("/sw.js", ctx -> serveSharedFile(ctx, webDist, "sw.js"));

            // Serve each independent SPA's index.html for its own path
            // space. This is the server-side fix for the "URL grows
            // /portal/index.html/index.html/..." bug: the SPA's own
            // entry point is what should be served, never the hub
            // (which would forward again and re-append /index.html).
            app.get("/portal", ctx -> serveSpaIndexHtml(ctx, webDist, "portal"));
            app.get("/portal/*", ctx -> serveSpaIndexHtml(ctx, webDist, "portal"));
            app.get("/ops", ctx -> serveSpaIndexHtml(ctx, webDist, "ops"));
            app.get("/ops/*", ctx -> serveSpaIndexHtml(ctx, webDist, "ops"));
            app.get("/noc", ctx -> serveSpaIndexHtml(ctx, webDist, "noc"));
            app.get("/noc/*", ctx -> serveSpaIndexHtml(ctx, webDist, "noc"));

            // Serve the root hub for `/` and any other non-API path
            // (legacy aliases, accidental 404s, etc.). The hub does
            // its own client-side forwarding for the remaining legacy
            // paths (/business, /business-portal, /runs/:ws/:id, etc.)
            app.get("/", ctx -> serveIndexHtml(ctx, webDist));
            app.get("/*", ctx -> {
                String path = ctx.path();
                if (path.startsWith("/api/") || path.startsWith("/health")) {
                    return; // Let API routes handle
                }
                serveIndexHtml(ctx, webDist);
            });
        } else {
            logger.warn("Web dist directory not found: {}. Static assets will not be served.", webDist);
        }
    }

    /**
     * Serve one of the independent SPAs (portal / ops / noc) by
     * returning its index.html. The SPA's React Router then takes
     * over client-side. This is what `/portal/teams` should resolve
     * to — not the hub (which would forward to /portal/index.html
     * and add another segment on every reload).
     */
    private void serveSpaIndexHtml(Context ctx, java.nio.file.Path webDist, String spaName) {
        java.nio.file.Path indexPath = webDist.resolve(spaName).resolve("index.html");
        serveIndexHtmlFromPath(ctx, indexPath);
    }

    /** 返回 index.html 并注入会话令牌 — 供 SPA fallback 使用。 */
    private void serveIndexHtml(Context ctx, java.nio.file.Path webDist) {
        java.nio.file.Path indexPath = webDist.resolve("index.html");
        serveIndexHtmlFromPath(ctx, indexPath);
    }

    private void serveIndexHtmlFromPath(Context ctx, java.nio.file.Path indexPath) {
        if (java.nio.file.Files.exists(indexPath)) {
            try {
                String html = java.nio.file.Files.readString(indexPath);
                // Inject session token + short-lived SSE token (refreshed on each page load).
                String sseToken = issueSseToken();
                String inject = "<head><script>"
                    + "window.__HERMES_SESSION_TOKEN__=\"" + sessionToken + "\";"
                    + "window.__HERMES_SSE_TOKEN__=\"" + sseToken + "\";"
                    + "</script>";
                html = html.replace("<head>", inject);
                ctx.contentType("text/html");
                ctx.result(html);
            } catch (Exception e) {
                logger.error("Failed to serve index.html", e);
                ctx.status(500).result("Internal Server Error");
            }
        } else {
            ctx.status(404).result("index.html not found");
        }
    }

    /**
     * 解析前端静态资源目录路径。
     * <p>查找顺序：HERMES_WEB_DIST 环境变量 → CWD 候选路径 → JAR 同级目录候选路径。</p>
     *
     * @return 解析到的 web_dist 路径（找不到时返回第一个候选路径，由调用方处理）
     */
    java.nio.file.Path resolveWebDistPath() {
        String explicit = System.getenv("HERMES_WEB_DIST");
        if (explicit != null && !explicit.isBlank()) {
            java.nio.file.Path p = java.nio.file.Path.of(explicit).toAbsolutePath().normalize();
            logger.info("HERMES_WEB_DIST set, using {}", p);
            return p;
        }

        // Try paths relative to the current working directory first.
        java.util.List<java.nio.file.Path> cwdCandidates = java.util.List.of(
            java.nio.file.Path.of("hermes_cli", "web_dist"),
            java.nio.file.Path.of("web_dist"),
            java.nio.file.Path.of("web", "dist")
        );

        for (java.nio.file.Path candidate : cwdCandidates) {
            java.nio.file.Path normalized = candidate.toAbsolutePath().normalize();
            boolean exists = java.nio.file.Files.exists(normalized.resolve("index.html"));
            logger.debug("CWD candidate: {} -> index.html exists={}", normalized, exists);
            if (exists) {
                logger.info("Resolved web_dist from CWD: {}", normalized);
                return normalized;
            }
        }

        // Fallback: try paths relative to the jar / class location so the server
        // works regardless of the working directory.
        try {
            java.nio.file.Path jarDir = java.nio.file.Path.of(
                getClass().getProtectionDomain().getCodeSource().getLocation().toURI()
            ).getParent();
            logger.debug("Jar directory: {}", jarDir);
            if (jarDir != null) {
                java.util.List<java.nio.file.Path> jarCandidates = java.util.List.of(
                    jarDir.resolve("hermes_cli").resolve("web_dist"),
                    jarDir.resolve("web_dist"),
                    jarDir.resolve("web").resolve("dist")
                );
                for (java.nio.file.Path candidate : jarCandidates) {
                    java.nio.file.Path normalized = candidate.normalize();
                    boolean exists = java.nio.file.Files.exists(normalized.resolve("index.html"));
                    logger.debug("Jar candidate: {} -> index.html exists={}", normalized, exists);
                    if (exists) {
                        logger.info("Resolved web_dist from jar location: {}", normalized);
                        return normalized;
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Could not resolve web_dist from jar location: {}", e.getMessage());
        }

        // Last resort: return the first cwd candidate even if it doesn't exist
        // (caller will log a warning and skip static route registration).
        java.nio.file.Path fallback = cwdCandidates.get(0).toAbsolutePath().normalize();
        logger.warn("No web_dist found; falling back to {} (static routes will be skipped)", fallback);
        return fallback;
    }

    /** Serve a single shared root-level file (favicon, manifest, sw). */
    private void serveSharedFile(Context ctx, java.nio.file.Path webDist, String filename) {
        java.nio.file.Path filePath = webDist.resolve(filename);
        if (java.nio.file.Files.exists(filePath) && java.nio.file.Files.isRegularFile(filePath)) {
            try {
                ctx.contentType(getContentType(filePath.toString()));
                ctx.result(java.nio.file.Files.readAllBytes(filePath));
            } catch (Exception e) {
                logger.error("Error serving shared file {}: {}", filename, e.getMessage());
                ctx.status(500).result("Error");
            }
        } else {
            ctx.status(404).result("Not found");
        }
    }

    /** 安全地提供静态文件 — 通过 basePath 校验防止目录穿越。 */
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

    /** 根据文件扩展名推断 MIME Content-Type。 */
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

    /** 构造 Dashboard 状态响应 — 包含版本、Gateway 状态、租户数量等。 */
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
