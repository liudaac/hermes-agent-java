package com.nousresearch.hermes.dashboard;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.nousresearch.hermes.dashboard.handlers.*;
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
import com.nousresearch.hermes.config.HermesConfig;
import java.nio.file.Path;
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
    private final CronHandler cronHandler;
    private final OAuthProvidersHandler oauthProvidersHandler;
    private final AnalyticsHandler analyticsHandler;
    private final OrgOverviewHandler orgOverviewHandler = new OrgOverviewHandler();
    private final OrgControlCenterHandler orgControlCenterHandler;
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
        this.sessionToken = generateSessionToken();

        // Initialize handlers
        this.configHandler = new ConfigHandler(config);
        this.sessionHandler = new SessionHandler();
        // Wire AI-native org overview/control center to real tenant data
        this.orgOverviewHandler.setTenantManager(tenantManager);
        this.orgControlCenterHandler = new OrgControlCenterHandler(tenantManager);
        this.envHandler = new EnvHandler();
        this.logsHandler = new LogsHandler();
        this.skillsHandler = new SkillsHandler();
        this.toolsHandler = new ToolsHandler();
        this.gatewayHandler = new GatewayHandler();
        this.cronHandler = new CronHandler(
            Path.of(System.getProperty("user.home"), ".hermes", "dashboard-cron-jobs.json"),
            true,
            new com.nousresearch.hermes.dashboard.handlers.AgentCronRunner(config)
        );
        this.oauthProvidersHandler = new OAuthProvidersHandler();
        this.analyticsHandler = new AnalyticsHandler();
        this.workspaceService = new WorkspaceService(tenantManager);
        this.teamBlueprintService = new TeamBlueprintService(workspaceService);
        this.scenarioService = new ScenarioService(workspaceService, teamBlueprintService);
        this.scenarioService.setScenarioIntentAdapter(new com.nousresearch.hermes.scenario.ScenarioIntentAdapter(workspaceService, tenantManager));
        this.businessApprovalService = new BusinessApprovalService(workspaceService);
        this.businessRunService = new BusinessRunService(workspaceService, teamBlueprintService, scenarioService);
        this.businessInsightService = new BusinessInsightService(workspaceService, teamBlueprintService, businessRunService, businessApprovalService);
        this.promptAssetService = new PromptAssetService(workspaceService);
        this.evolutionProposalService = new EvolutionProposalService(workspaceService, teamBlueprintService);
        this.policyService = new PolicyService(workspaceService, teamBlueprintService);
        this.scenarioService.setPolicyService(policyService, businessApprovalService);
        this.scenarioService.setBusinessMemoryNoteService(new com.nousresearch.hermes.memory.BusinessMemoryNoteService(workspaceService));
        // Wire tool-level approval coordinator into team runtime
        var toolApprovalCoordinator = new com.nousresearch.hermes.business.approval.ToolApprovalCoordinator(
            workspaceService, businessApprovalService);
        this.scenarioService.getTeamBlueprintRuntime().setToolApprovalCoordinator(toolApprovalCoordinator);
        this.toolApprovalCoordinator = toolApprovalCoordinator;
        this.evalSetService = new EvalSetService(workspaceService, scenarioService);
        this.canaryReleaseService = new CanaryReleaseService(workspaceService, teamBlueprintService);
        // Wire canary into scenario service for traffic-based version routing + metrics
        this.scenarioService.setCanaryReleaseService(canaryReleaseService);
        this.scenarioService.setPlanReflectionService(
            new PlanReflectionService(new com.nousresearch.hermes.model.ModelClient(config.getModelConfig())));
        this.quickTeamBuilderService = new QuickTeamBuilderService(
            new com.nousresearch.hermes.model.ModelClient(config.getModelConfig()));
        this.activeMemoryService = new BusinessMemoryNoteService(workspaceService);
        this.businessPortalFoundationFacade = new BusinessPortalAdapterRegistry(
            workspaceService,
            tenantManager,
            promptAssetService
        ).createFacade();
        // Extended orchestration services
        this.businessEventBus = new com.nousresearch.hermes.business.event.BusinessEventBus();
        this.slaManager = new com.nousresearch.hermes.business.sla.SLAManager(businessRunService, businessApprovalService);
        this.deadLetterQueue = new com.nousresearch.hermes.business.dlq.DeadLetterQueue();
        this.approvalAnalytics = new com.nousresearch.hermes.business.analytics.ApprovalAnalytics(businessApprovalService);
        this.humanOverrideService = new com.nousresearch.hermes.business.humanintheloop.HumanOverrideService(workspaceService, businessRunService);
        this.workflowService = new com.nousresearch.hermes.business.workflow.BusinessWorkflowService(
            new WorkflowEngine(java.nio.file.Paths.get(System.getProperty("user.home"), ".hermes", "workflows")),
            scenarioService, workspaceService, businessRunService, slaManager);
        this.connectorRegistry = new com.nousresearch.hermes.connector.ConnectorRegistry();
        this.ecommerceScenarioFactory = new com.nousresearch.hermes.business.vertical.ecommerce.EcommerceScenarioFactory(
            workspaceService, scenarioService, teamBlueprintService);

        // Wire event bus into services
        this.slaManager.setEventBus(businessEventBus);
        this.humanOverrideService.setEventBus(businessEventBus);
        this.deadLetterQueue.setEventBus(businessEventBus);

        // ---- ACP 集成：初始化 Agent Collaboration Protocol 服务器 ----
        com.nousresearch.hermes.acp.integration.AcpIntegration acpIntegration = new com.nousresearch.hermes.acp.integration.AcpIntegration();

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
                ctx.skipRemainingHandlers();
                return;
            }

            // Auth middleware for /api/ routes
            String path = ctx.path();
            if (path.startsWith("/api/") && !publicApiPaths.contains(path) && !path.startsWith("/api/plugins/")) {
                String auth = ctx.header("Authorization");
                String expected = "Bearer " + sessionToken;

                // EventSource (SSE) cannot set custom headers — allow ?token=... on SSE routes.
                if ((auth == null || !hmacCompare(auth, expected))
                    && (path.equals("/api/logs/tail")
                        || path.matches("^/api/cron/jobs/[^/]+/runs/stream$"))) {
                    String tokenParam = ctx.queryParam("token");
                    if (tokenParam != null && hmacCompare(tokenParam, sessionToken)) {
                        auth = expected;
                    }
                }

                if (auth == null || !hmacCompare(auth, expected)) {
                    ctx.status(401).result(JSON.toJSONString(new JSONObject()
                        .fluentPut("detail", "Unauthorized")));
                    ctx.skipRemainingHandlers();
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

        // ========== AI原生组织 API ==========
        app.get("/api/organization/overview", orgOverviewHandler::getOverview);
        app.get("/api/organization/agents", orgOverviewHandler::getAgents);
        app.get("/api/organization/health", orgOverviewHandler::getHealth);

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

    /** 返回 index.html 并注入会话令牌 — 供 SPA fallback 使用。 */
    private void serveIndexHtml(Context ctx, java.nio.file.Path webDist) {
        java.nio.file.Path indexPath = webDist.resolve("index.html");
        if (java.nio.file.Files.exists(indexPath)) {
            try {
                String html = java.nio.file.Files.readString(indexPath);
                // Inject session token
                html = html.replace("<head>",
                    "<head><script>window.__HERMES_SESSION_TOKEN__=\"" + sessionToken + "\";</script>");
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
