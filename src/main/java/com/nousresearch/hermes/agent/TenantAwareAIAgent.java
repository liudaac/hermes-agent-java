package com.nousresearch.hermes.agent;

import com.nousresearch.hermes.collaboration.AgentRuntimeProfile;
import com.nousresearch.hermes.collaboration.GovernancePolicy;
import com.nousresearch.hermes.collaboration.OrgHealthChecker;
import com.nousresearch.hermes.config.HermesConfig;
import com.nousresearch.hermes.memory.PromptContextBuilder;
import com.nousresearch.hermes.model.ModelMessage;
import com.nousresearch.hermes.monitoring.AgentEvalMetrics;
import com.nousresearch.hermes.model.ToolCall;
import com.nousresearch.hermes.plugin.PluginManager;
import com.nousresearch.hermes.plugin.hook.HookEngine;
import com.nousresearch.hermes.plugin.hook.HookType;
import com.nousresearch.hermes.tenant.core.*;
import com.nousresearch.hermes.tenant.quota.QuotaExceededException;
import com.nousresearch.hermes.tools.TenantAwareToolDispatcher;
import com.nousresearch.hermes.approval.ApprovalMessageHandler;
import com.nousresearch.hermes.approval.ApprovalSystem;
import com.nousresearch.hermes.tools.ToolRegistry;
import com.nousresearch.hermes.skills.BackgroundReviewPrompts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 统一的、租户感知的 AI Agent
 *
 * 设计原则：
 * - 所有 Agent 实例都关联到一个租户上下文
 * - 单用户场景使用默认租户 (tenantId = "default")
 * - 多租户场景使用指定的租户 ID
 * - 向后兼容：替换原有的 AIAgent，保持 API 一致
 */
public class TenantAwareAIAgent {
    private static final Logger logger = LoggerFactory.getLogger(TenantAwareAIAgent.class);

    private final String tenantId;
    private final String sessionId;
    private final TenantContext tenantContext;

    // Persistent tool dispatcher (created once, reused for all calls)
    private TenantAwareToolDispatcher toolDispatcher;

    // Per-tenant approval system
    private ApprovalSystem approvalSystem;
    private ApprovalMessageHandler approvalMessageHandler;
    private final HermesConfig config;

    // 核心组件（复用现有逻辑）
    private final com.nousresearch.hermes.model.ModelClient modelClient;
    private final IterationBudget iterationBudget;
    private final List<ModelMessage> conversationHistory;
    private final AtomicBoolean interrupted;
    /** Soft context window limit (tokens, ~4 chars/token). Default 100k tokens = 400k chars. */
    private static final int DEFAULT_CONTEXT_CHARS = 400_000;

    // 租户隔离的子组件
    private final com.nousresearch.hermes.memory.MemoryManager memoryManager;
    private com.nousresearch.hermes.memory.PromptContextBuilder memoryCardIntegrator;
    private boolean smartMemoryCardEnabled;
    private com.nousresearch.hermes.trajectory.TrajectoryCollector trajectoryCollector;
    private com.nousresearch.hermes.learning.LearningPipeline learningPipeline;
    private ReflectionEngine reflectionEngine;

    private ConfidenceCalibrator confidenceCalibrator;
    private com.nousresearch.hermes.tools.ToolPerformanceTracker toolPerformanceTracker;
    private CognitiveTraceCollector cognitiveTraceCollector;
    private com.nousresearch.hermes.monitoring.AgentEvalMetrics evalMetrics;

    // ======== AI原生组织：协作组件 ========
    private final String agentId;
    private final AgentRuntimeProfile agentRole;
    private final GovernancePolicy governancePolicy;
    private OrgHealthChecker orgHealthChecker;
    private com.nousresearch.hermes.org.evolution.SelfEvolutionEngine evolutionEngine;
    private com.nousresearch.hermes.collaboration.TeamRuntime team;
    private com.nousresearch.hermes.org.observe.AgentTrace currentTrace;
    private double lastTaskScore = 0.0;

    // Nudge intervals
    private int memoryNudgeInterval = 10;
    private int skillNudgeInterval = 10;
    private int turnsSinceMemory = 0;
    private int itersSinceSkill = 0;
    private int userTurnCount = 0;
    private volatile boolean autoSkillsLoaded = false;
    private volatile String customSystemPrompt;
    private volatile Map<String, Object> modelParams;

    // ===== Declarative System Prompt Assembly (P0-1) =====
    private com.nousresearch.hermes.harness.prompt.SystemPromptAssembler promptAssembler;
    private Runnable promptDisposer;

    private static final int AUTO_SAVE_INTERVAL = 5;

    // ===== P3-2: Agent-level MaintenanceScheduler (persists across calls) =====
    private final com.nousresearch.hermes.harness.maintenance.MaintenanceScheduler maintenanceScheduler =
        new com.nousresearch.hermes.harness.maintenance.MaintenanceScheduler();
    private volatile com.nousresearch.hermes.harness.AgentContext latestContext;

    // ===== Extracted subsystems =====
    private ToolExecutionGateway toolGateway;
    private SessionLifecycle sessionLifecycle;

    // ===== 工具级审批挂起状态 (delegated to ToolExecutionGateway) =====

    /** Pending background-review summaries, flushed at the start of the next turn. */
    private final java.util.concurrent.ConcurrentLinkedQueue<String> pendingReviewSummaries =
        new java.util.concurrent.ConcurrentLinkedQueue<>();

    // ==================== Factory Methods ====================

    /**
     * 创建默认租户 Agent（单用户场景）- 向后兼容
     */
    public static TenantAwareAIAgent createDefault(HermesConfig config) {
        return new TenantAwareAIAgent("default", config, null, true);
    }

    /**
     * 创建指定租户 Agent（多租户场景）
     */
    public static TenantAwareAIAgent forTenant(String tenantId, HermesConfig config) {
        return new TenantAwareAIAgent(tenantId, config, null, true);
    }

    /**
     * Create an agent bound to an already-resolved tenant context.
     * This keeps Gateway/TenantManager runtime state, quota, sessions and sandboxing on one object graph.
     */
    public static TenantAwareAIAgent forContext(TenantContext context, String sessionId, HermesConfig config) {
        return new TenantAwareAIAgent(context, config, sessionId);
    }

    /**
     * Create an agent from a blueprint definition with an explicit agentId and role.
     * Used by TeamBlueprintRuntime to spin up team members with stable IDs
     * that the ScenarioOrchestrator can route to.
     */
    public static TenantAwareAIAgent forBlueprint(TenantContext context, String agentId,
                                                  AgentRuntimeProfile role, String sessionId, HermesConfig config) {
        return new TenantAwareAIAgent(context, agentId, role, sessionId, config);
    }

    /**
     * 从网关消息创建 Agent（自动识别租户）
     */
    public static TenantAwareAIAgent fromGateway(String platform, String channelId,
                                                    String userId, HermesConfig config) {
        String tenantId = resolveTenantId(platform, channelId, userId);
        String sessionId = platform + ":" + channelId;
        return new TenantAwareAIAgent(tenantId, config, sessionId, false);
    }

    // ==================== Constructors ====================

    /**
     * 向后兼容：原有 AIAgent 的构造函数
     */
    public TenantAwareAIAgent(HermesConfig config) {
        this("default", config, null, true);
    }

    /**
     * 向后兼容：原有 AIAgent 的构造函数（带 sessionId）
     */
    public TenantAwareAIAgent(HermesConfig config, String sessionId) {
        this("default", config, sessionId, true);
    }

    private TenantAwareAIAgent(TenantContext context, HermesConfig config,
                                String explicitSessionId) {
        this(context, null, null, explicitSessionId, config, false);
    }

    /**
     * Blueprint-aware constructor: uses an explicit agentId and role instead
     * of generating a random ID and default role. Used by TeamBlueprintRuntime.
     */
    private TenantAwareAIAgent(TenantContext context, String explicitAgentId,
                                AgentRuntimeProfile explicitRole, String explicitSessionId,
                                HermesConfig config) {
        this(context, explicitAgentId, explicitRole, explicitSessionId, config, true);
    }

    /**
     * Unified internal constructor for tenant-context-bound agents.
     * @param registerOnBus if true, registers the agent on the TenantBus for team collaboration
     */
    private TenantAwareAIAgent(TenantContext context, String explicitAgentId,
                                AgentRuntimeProfile explicitRole, String explicitSessionId,
                                HermesConfig config, boolean registerOnBus) {
        if (context == null) {
            throw new IllegalArgumentException("TenantContext is required");
        }
        this.tenantId = context.getTenantId();
        this.config = config != null ? config : new HermesConfig();
        this.sessionId = explicitSessionId != null ? explicitSessionId
            : "cli_" + UUID.randomUUID().toString().substring(0, 8);
        this.tenantContext = context;
        this.toolDispatcher = new TenantAwareToolDispatcher(tenantContext, ToolRegistry.getInstance());
        // Negotiator disabled for Jarvis - causes blocking without approval UI
        // toolDispatcher.setNegotiator(tenantContext.getNegotiator());
        // Jarvis agents get broader file access (read user home for config inspection)
        if (explicitAgentId != null && explicitAgentId.startsWith("jarvis-") && tenantContext != null
                && tenantContext.getFileSandbox() != null) {
            var sandbox = tenantContext.getFileSandbox();
            String home = System.getProperty("user.home", "/root");
            var cfg = sandbox.getConfig();
            var allowed = new java.util.HashSet<>(cfg.getAllowedPaths());
            allowed.add(java.nio.file.Path.of(home));
            allowed.add(java.nio.file.Path.of(home, ".harness"));
            cfg.setAllowedPaths(allowed);
            logger.info("Jarvis agent - sandbox expanded to include {}", home);
        }
        // B1: Resolve model config from TenantConfig (tenant-scoped), fallback to global HermesConfig
        this.modelClient = new com.nousresearch.hermes.model.ModelClient(context, this.config);
        this.modelClient.setSessionId(this.sessionId); // B4: billing traceability
        // Wire tool call prelude for explainability + dry-run + graceful reject
        this.toolDispatcher.setToolCallPrelude(new com.nousresearch.hermes.tools.ToolCallPrelude(
            this.modelClient));

        this.iterationBudget = new IterationBudget(this.config.getMaxTurns());
        this.memoryManager = new com.nousresearch.hermes.memory.MemoryManager(tenantId);
        initPromptContextBuilder();
        this.toolPerformanceTracker = new com.nousresearch.hermes.tools.ToolPerformanceTracker(
            com.nousresearch.hermes.config.Constants.getHermesHome().resolve("tenants")
                .resolve(tenantId).resolve("state"));
        this.conversationHistory = new ArrayList<>();
        this.cognitiveTraceCollector = new CognitiveTraceCollector(
            this.sessionId, com.nousresearch.hermes.config.Constants.getHermesHome().resolve("tenants").resolve(tenantId).resolve("trajectory"));
        this.evalMetrics = new com.nousresearch.hermes.monitoring.AgentEvalMetrics();
        this.interrupted = new AtomicBoolean(false);

        // ======== AI原生组织：绑定角色与治理策略 ========
        if (explicitAgentId != null && !explicitAgentId.isBlank()) {
            this.agentId = explicitAgentId;
        } else {
            this.agentId = "agent_" + UUID.randomUUID().toString().substring(0, 8);
        }

        if (explicitRole != null) {
            context.registerAgentRole(this.agentId, explicitRole);
            this.agentRole = explicitRole;
        } else {
            AgentRuntimeProfile existingRole = context.getAgentRole(this.agentId);
            if (existingRole != null) {
                this.agentRole = existingRole;
            } else {
                this.agentRole = buildDefaultRole();
                context.registerAgentRole(this.agentId, this.agentRole);
            }
        }
        this.governancePolicy = context.getGovernancePolicy();
        this.orgHealthChecker = tenantContext.getOrgHealthChecker();
        this.evolutionEngine = tenantContext.getEvolutionEngine();

        initializeLearningComponents();
        initializeTools();
        // Initialize extracted subsystems (toolGateway needed by initTenantApproval)
        this.toolGateway = new ToolExecutionGateway(this);
        this.sessionLifecycle = new SessionLifecycle(this);
        initTenantApproval();
        tenantContext.initCollaboration();

        // Register on bus only for long-lived team agents (blueprint scenario)
        if (registerOnBus) {
            try {
                var team = this.tenantContext.getTeamManager().getOrCreateDefaultTeam(this.agentId);
                this.team = team;
                var bus = this.tenantContext.getTenantBus();
                bus.register(this.agentId, msg -> handleBusMessage(msg));
                logger.info("Agent {} joined team '{}' and registered on bus",
                    this.agentId, team.getName());
            } catch (Exception e) {
                logger.warn("Failed to register agent on team/bus: {}", e.getMessage());
            }
        }

        logger.info("Agent {} bound to role '{}' in tenant {}",
            this.agentId, this.agentRole.getRoleName(), this.tenantId);

        logger.info("Created TenantAwareAIAgent for existing tenant context: {}, session: {}",
            this.tenantId, this.sessionId);
    }

    private TenantAwareAIAgent(String tenantId, HermesConfig config,
                                String explicitSessionId, boolean initializeDefaultTenant) {
        this.tenantId = tenantId;
        // 如果 config 为 null，使用默认配置
        this.config = config != null ? config : new HermesConfig();
        this.sessionId = explicitSessionId != null ? explicitSessionId
            : "cli_" + UUID.randomUUID().toString().substring(0, 8);

        // 获取或创建租户上下文
        TenantManager manager = initializeDefaultTenant ? ensureTenantManager() : null;
        if (manager != null) {
            this.tenantContext = manager.getOrCreateTenant(tenantId, createDefaultRequest());
            // B1: tenant-aware ModelClient
            this.modelClient = new com.nousresearch.hermes.model.ModelClient(this.tenantContext, this.config);
            this.modelClient.setSessionId(this.sessionId); // B4: billing traceability
            this.toolDispatcher = new TenantAwareToolDispatcher(tenantContext, ToolRegistry.getInstance());
            if (tenantContext != null) {
                // Negotiator disabled for Jarvis - causes blocking without approval UI
                // toolDispatcher.setNegotiator(tenantContext.getNegotiator());
                // Jarvis agents get broader file access
                if (this.sessionId != null && this.sessionId.startsWith("jarvis-")
                        && tenantContext.getFileSandbox() != null) {
                    var sandbox = tenantContext.getFileSandbox();
                    String home = System.getProperty("user.home", "/root");
                    var cfg = sandbox.getConfig();
                    var allowed = new java.util.HashSet<>(cfg.getAllowedPaths());
                    allowed.add(java.nio.file.Path.of(home));
                    allowed.add(java.nio.file.Path.of(home, ".harness"));
                    cfg.setAllowedPaths(allowed);
                    logger.info("Jarvis agent - sandbox expanded to include {}", home);
                }
            }
            this.toolDispatcher.setToolCallPrelude(new com.nousresearch.hermes.tools.ToolCallPrelude(
                this.modelClient));
        } else {
            // Fallback for non-tenant mode
            this.tenantContext = null;
            this.modelClient = new com.nousresearch.hermes.model.ModelClient(this.config.getModelConfig());
            this.toolDispatcher = new TenantAwareToolDispatcher(null, ToolRegistry.getInstance());
            this.toolDispatcher.setToolCallPrelude(new com.nousresearch.hermes.tools.ToolCallPrelude(
                this.modelClient));
        }

        // 初始化核心组件
        this.iterationBudget = new IterationBudget(this.config.getMaxTurns());
        this.memoryManager = new com.nousresearch.hermes.memory.MemoryManager(tenantId);
        initPromptContextBuilder();
        this.toolPerformanceTracker = new com.nousresearch.hermes.tools.ToolPerformanceTracker(
            com.nousresearch.hermes.config.Constants.getHermesHome().resolve("tenants").resolve(tenantId).resolve("state"));
        this.cognitiveTraceCollector = new CognitiveTraceCollector(
            this.sessionId, com.nousresearch.hermes.config.Constants.getHermesHome().resolve("tenants").resolve(tenantId).resolve("trajectory"));
        this.evalMetrics = new com.nousresearch.hermes.monitoring.AgentEvalMetrics();
        this.conversationHistory = new ArrayList<>();
        this.interrupted = new AtomicBoolean(false);

        // ======== AI原生组织：绑定角色与治理策略 ========
        this.agentId = "agent_" + UUID.randomUUID().toString().substring(0, 8);
        if (this.tenantContext != null) {
            AgentRuntimeProfile existingRole = this.tenantContext.getAgentRole(this.agentId);
            if (existingRole != null) {
                this.agentRole = existingRole;
            } else {
                this.agentRole = buildDefaultRole();
                this.tenantContext.registerAgentRole(this.agentId, this.agentRole);
            }
            this.governancePolicy = this.tenantContext.getGovernancePolicy();
            this.orgHealthChecker = this.tenantContext.getOrgHealthChecker();
            this.evolutionEngine = this.tenantContext.getEvolutionEngine();
            // ======== AI原生组织：第三刀--团队与总线注册 ========
            // 1) 加入默认团队（singleton team，确保每个 agent 至少有归属）
            // 2) 自动注册到 TenantBus，让队友能 discover 并 message 它
            try {
                this.tenantContext.initCollaboration();
                var team = this.tenantContext.getTeamManager().getOrCreateDefaultTeam(this.agentId);
                this.team = team;
                var bus = this.tenantContext.getTenantBus();
                bus.register(this.agentId, msg -> handleBusMessage(msg));
                logger.info("Agent {} joined team '{}' and registered on bus",
                    this.agentId, team.getName());
            } catch (Exception e) {
                logger.warn("Failed to register agent on team/bus: {}", e.getMessage());
            }
            logger.info("Agent {} bound to role '{}' in tenant {}",
                this.agentId, this.agentRole.getRoleName(), this.tenantId);
        } else {
            this.agentRole = buildDefaultRole();
            this.governancePolicy = new GovernancePolicy();
            logger.info("Agent {} bound to standalone role '{}' (no tenant context)",
                this.agentId, this.agentRole.getRoleName());
        }

        // 初始化学习组件
        initializeLearningComponents();

        // 初始化工具
        initializeTools();
        // Initialize extracted subsystems (toolGateway needed by initTenantApproval)
        this.toolGateway = new ToolExecutionGateway(this);
        this.sessionLifecycle = new SessionLifecycle(this);
        initTenantApproval();

        logger.info("Created TenantAwareAIAgent for tenant: {}, session: {}", tenantId, this.sessionId);
    }

    private static TenantManager ensureTenantManager() {
        try {
            return new TenantManager();
        } catch (Exception e) {
            logger.warn("Failed to initialize TenantManager, running in non-tenant mode: {}", e.getMessage());
            return null;
        }
    }

    /** Current user ID for user-dimension memory isolation (set by adapters). */
    private volatile String currentUserId;

    /**
     * Set the current user ID for user-dimension memory isolation.
     * This is read by the TenantAIAgent wrapper to scope MemoryStore calls.
     * @param userId user identifier, or null to clear
     */
    public void setUserId(String userId) {
        this.currentUserId = userId;
    }

    /**
     * Get the current user ID (for TenantAIAgent wrapper to read).
     * @return current user ID, or null if not set
     */
    public String getCurrentUserId() {
        return currentUserId;
    }

    // ==================== Public API ====================

    /**
     * 处理消息（向后兼容）
     */
    public String processMessage(String message) {
        // 1. 检查租户状态
        if (tenantContext != null && !tenantContext.isActive()) {
            return "Error: Tenant is not active (" + tenantContext.getState() + ")";
        }

        // 2. 检查配额
        if (tenantContext != null) {
            try {
                tenantContext.getQuotaManager().checkDailyRequestQuota();
            } catch (QuotaExceededException e) {
                return "Error: " + e.getMessage();
            }
        }

        // 3. 更新活动状态
        if (tenantContext != null) {
            tenantContext.updateActivity();
        }

        // 4. 确保 Web/Gateway 路径也加载租户自动技能
        ensureAutoSkillsLoaded("web");

        // ======== AI原生组织：第五刀--可观测性 ========
        // 开启一次追踪，整次请求的工具调用、错误、决策都会被记录
        com.nousresearch.hermes.org.observe.AgentTrace currentTrace = null;
        if (tenantContext != null) {
            currentTrace = tenantContext.getObservability().startTrace(agentId, sessionId, message);
        }
        this.currentTrace = currentTrace;

        // 5. 执行核心处理逻辑
        String result;
        try {
            result = doProcessMessage(message);
        } catch (Exception e) {
            if (currentTrace != null) {
                currentTrace.step(com.nousresearch.hermes.org.observe.AgentTrace.Step.error(e.getMessage()));
                currentTrace.end(com.nousresearch.hermes.org.observe.AgentTrace.Status.FAILED);
                tenantContext.getObservability().completeTrace(currentTrace);
            }
            throw e;
        }

        // 6. 完成追踪
        if (currentTrace != null) {
            currentTrace.end(com.nousresearch.hermes.org.observe.AgentTrace.Status.SUCCESS);
            tenantContext.getObservability().completeTrace(currentTrace);
        }
        return result;
    }

    /**
     * 流式处理消息
     */
    public void processMessageStream(String message, java.util.function.Consumer<String> chunkConsumer) {
        if (tenantContext != null && !tenantContext.isActive()) {
            chunkConsumer.accept("Error: Tenant is not active");
            return;
        }

        if (tenantContext != null) {
            try {
                tenantContext.getQuotaManager().checkDailyRequestQuota();
            } catch (QuotaExceededException e) {
                chunkConsumer.accept("Error: " + e.getMessage());
                return;
            }
            tenantContext.updateActivity();
        }

        ensureAutoSkillsLoaded("web-stream");
        doProcessMessageStream(message, chunkConsumer);
    }

    /**
     * 结束会话
     */
    public void endSession(boolean completed) {
        sessionLifecycle.endSession(completed);
    }

    /**
     * Return debug info for the current session (usage + tool calls).
     * Called by the dashboard playground after a message round.
     */
    public Map<String, Object> getSessionDebugInfo() {
        return sessionLifecycle.getSessionDebugInfo();
    }

    /**
     * Set the org health checker for collaborative monitoring.
     */
    public void setOrgHealthChecker(OrgHealthChecker checker) {
        this.orgHealthChecker = checker;
    }

    /**
     * Override the system prompt for this agent instance.
     * If null/blank, falls back to the default built-in prompt.
     */
    public void setSystemPrompt(String prompt) {
        this.customSystemPrompt = prompt;
        // Rebuild the first system message in conversation history if present
        if (!conversationHistory.isEmpty() && "system".equals(conversationHistory.get(0).getRole())) {
            conversationHistory.set(0, ModelMessage.system(buildSystemPrompt()));
        }
    }

    public String getSystemPrompt() {
        return customSystemPrompt != null && !customSystemPrompt.isBlank()
            ? customSystemPrompt
            : buildSystemPrompt();
    }

    /**
     * Override model parameters (temperature, max_tokens, top_p, etc.)
     * for the next chat completion call. Pass null to clear overrides.
     */
    public void setModelParams(Map<String, Object> params) {
        this.modelParams = params;
    }

    public Map<String, Object> getModelParams() {
        return modelParams;
    }

    /**
     * 运行交互式 CLI 模式（向后兼容）
     */
    public void runInteractive() {
        System.out.println("╔════════════════════════════════════════╗");
        System.out.println("║     Hermes Agent Java - Ready          ║");
        System.out.println("║   Tenant: " + padRight(tenantId, 24) + "║");
        System.out.println("╚════════════════════════════════════════╝");
        System.out.println("Type 'exit' or '/quit' to exit");
        System.out.println("Type '/help' for commands");
        System.out.println();

        conversationHistory.add(ModelMessage.system(buildSystemPrompt()));

        ensureAutoSkillsLoaded("cli");

        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(System.in))) {

            while (!interrupted.get()) {
                System.out.print("\nYou: ");
                String input = reader.readLine();

                if (input == null || input.trim().isEmpty()) {
                    continue;
                }

                if (input.startsWith("/")) {
                    if (handleCommand(input)) {
                        break;
                    }
                    continue;
                }

                String response = processMessage(input);
                if (response != null && !response.isEmpty()) {
                    System.out.println("\nAssistant: " + response);
                }

                if (input.equalsIgnoreCase("exit") || input.equalsIgnoreCase("quit")) {
                    break;
                }
            }
        } catch (java.io.IOException e) {
            logger.error("IO error: {}", e.getMessage());
        }

        System.out.println("\nGoodbye!");
        endSession(true);
    }

    // ==================== Private Core Logic ====================

    private String doProcessMessage(String message) {
        // Build context (single interface between agent and loop)
        var ctx = new com.nousresearch.hermes.harness.AgentContext(this, config);
        this.latestContext = ctx;

        // P3-2: Interrupt any running maintenance (agent-level, persists across calls)
        interruptMaintenance();

        // P3-1: Auto-register run_code tool (lazy, idempotent)
        if (!ToolRegistry.getInstance().getAllToolNames().contains("run_code")) {
            try {
                ToolRegistry.getInstance().register(ctx.codeModeToolEntry());
            } catch (Exception e) {
                // Already registered by another thread, ignore
            }
        }

        // 1. PRE-LOOP
        boolean shouldReviewMemory = com.nousresearch.hermes.harness.AgentLoop.preLoop(ctx, message);

        // 2. LOOP
        String loopResponse;
        try {
            loopResponse = com.nousresearch.hermes.harness.AgentLoop.run(ctx, getEventEmitter());
        } catch (ToolApprovalRequiredException ex) {
            throw ex;
        }

        // 3. POST-LOOP
        String result = com.nousresearch.hermes.harness.AgentLoop.postLoop(ctx, loopResponse, shouldReviewMemory);

        // P3-2: Run maintenance jobs (idle time) - agent-level scheduler
        runMaintenance(ctx);

        return result;
    }

    private void doProcessMessageStream(String message, java.util.function.Consumer<String> chunkConsumer) {
        // Flush pending background-review summaries
        String reviewSummary;
        while ((reviewSummary = pendingReviewSummaries.poll()) != null) {
            chunkConsumer.accept("\n" + reviewSummary + "\n");
        }

        // Build context and delegate to AgentLoop (same path as non-streaming)
        var ctx = new com.nousresearch.hermes.harness.AgentContext(this, config);
        this.latestContext = ctx;

        // P3-2: Interrupt any running maintenance (agent-level, persists across calls)
        interruptMaintenance();

        // P3-1: Auto-register run_code tool (lazy, idempotent)
        if (!ToolRegistry.getInstance().getAllToolNames().contains("run_code")) {
            try {
                ToolRegistry.getInstance().register(ctx.codeModeToolEntry());
            } catch (Exception e) {
                // Already registered by another thread, ignore
            }
        }

        boolean shouldReviewMemory = com.nousresearch.hermes.harness.AgentLoop.preLoop(ctx, message);

        String loopResponse;
        try {
            loopResponse = com.nousresearch.hermes.harness.AgentLoop.run(ctx, getEventEmitter(), chunkConsumer);
        } catch (ToolApprovalRequiredException ex) {
            chunkConsumer.accept("\n⏸ [Approval required: " + ex.getToolName()
                + " - " + ex.getReason() + "]\n");
            throw ex;
        }

        com.nousresearch.hermes.harness.AgentLoop.postLoop(ctx, loopResponse, shouldReviewMemory);

        // P3-2: Run maintenance jobs (idle time) - agent-level scheduler
        runMaintenance(ctx);
    }


    public void recordModelUsage(com.nousresearch.hermes.model.ChatCompletionResponse response) {
        if (response == null || response.getUsage() == null) {
            return;
        }
        var usage = response.getUsage();
        long prompt = usage.getPromptTokens();
        long completion = usage.getCompletionTokens();
        long total = usage.getTotalTokens() > 0 ? usage.getTotalTokens() : prompt + completion;
        try {
            var session = new com.nousresearch.hermes.gateway.SessionManager(
                com.nousresearch.hermes.config.Constants.getHermesHome())
                .getSession(sessionId);
            session.recordUsage(
                response.getModel() != null ? response.getModel() : "unknown",
                prompt, completion,
                usage.getCachedPromptTokens(),
                usage.getReasoningTokens(),
                total);
        } catch (Exception e) {
            logger.debug("Failed to record model usage to session: {}", e.getMessage());
        }
        // Count this LLM call against tenant daily token quota. This is after-call accounting
        // so tokens already used are counted; the NEXT call will be blocked if quota exceeded.
        try {
            if (tenantContext != null && tenantContext.getQuotaManager() != null) {
                // Don't throw - just add to quota counter via addAndGet. If quota exceeded,
                // next tool/llm call that invokes checkTokenQuota will reject.
                tenantContext.getQuotaManager()
                    .getStoreIfAvailable()
                    .ifPresent(store -> store.addAndGetDailyTokens(total));
            }
        } catch (Exception e) {
            logger.debug("Failed to update tenant token quota: {}", e.getMessage());
        }
    }

    void recordToolCall(ToolCall toolCall, boolean ok, long durationMs) {
        toolGateway.recordToolCall(toolCall, ok, durationMs);
    }

    /**
     * Count how many tool-result messages appear after the last user message.
     */
    public int countToolsUsedThisTurn() {
        return toolGateway.countToolsUsedThisTurn();
    }

    // ==================== Approval ====================

    /**
     * Initialize per-tenant approval system and wire into the tool dispatcher.
     */
    void initTenantApproval() {
        toolGateway.initTenantApproval();
    }

    private void enforceContextBudget() {
        int totalChars = 0;
        for (ModelMessage m : conversationHistory) {
            String c = m.getContent();
            if (c != null) totalChars += c.length();
        }
        int targetChars = (int) (DEFAULT_CONTEXT_CHARS * 0.75);
        if (totalChars <= DEFAULT_CONTEXT_CHARS) return;
        // Drop oldest non-system messages to bring under target
        int i = 1; // preserve system (index 0)
        while (totalChars > targetChars && conversationHistory.size() > 6 && i < conversationHistory.size() - 4) {
            ModelMessage m = conversationHistory.remove(i);
            totalChars -= m.getContent() == null ? 0 : m.getContent().length();
        }
        logger.info("Enforced context budget, dropped to ~{} chars", totalChars);
    }

    // ==================== Tool Execution ====================

    /**
     * Execute a tool by name with args map. Used by CodeModeTool.
     */
    public String executeToolByName(String toolName, java.util.Map<String, Object> args) {
        return toolGateway.executeToolByName(toolName, args);
    }

    public String executeToolCall(ToolCall toolCall) {
        return toolGateway.executeToolCall(toolCall);
    }

    // Tool execution is now handled by TenantAwareToolDispatcher
    // This ensures proper sandbox isolation and permission checks

    // ======== AI原生组织：失败根因分析 ========
    private static com.nousresearch.hermes.org.evolution.FailureCase.RootCause determineRootCause(
            Exception e, String toolName) {
        return ToolExecutionGateway.determineRootCause(e, toolName);
    }

    /**
     * Get or create the per-agent evolution engine.
     */
    public com.nousresearch.hermes.org.evolution.SelfEvolutionEngine getEvolutionEngine() {
        if (evolutionEngine == null) {
            evolutionEngine = new com.nousresearch.hermes.org.evolution.SelfEvolutionEngine();
        }
        return evolutionEngine;
    }

    public void setEvolutionEngine(com.nousresearch.hermes.org.evolution.SelfEvolutionEngine engine) {
        this.evolutionEngine = engine;
    }

    // ======== AI原生组织：第三刀--Team-Aware Methods ========

    /** Get the team this agent belongs to. */
    public com.nousresearch.hermes.collaboration.TeamRuntime getTeam() {
        return team;
    }

    /** Set the team this agent belongs to (also adds agent to team). */
    public void setTeam(com.nousresearch.hermes.collaboration.TeamRuntime team) {
        this.team = team;
        if (team != null) {
            team.addMember(agentId);
        }
    }

    /**
     * Build a team-aware system prompt section.
     * Injects team context, members, and recent activity.
     */
    public String buildTeamAwarePrompt() {
        if (team == null) return "";
        return team.describeForPrompt();
    }

    /**
     * Handle a message received from the TenantBus.
     * The default behavior stores the message in team shared state
     * so the agent can reference it later via the system prompt.
     */
    private void handleBusMessage(com.nousresearch.hermes.collaboration.AgentMessage msg) {
        if (msg == null) return;
        try {
            // Record the incoming message in team state for awareness
            if (team != null) {
                String key = "msg:" + msg.getMessageId();
                team.putState(key, java.util.Map.of(
                    "from", msg.getSenderId(),
                    "action", msg.getAction(),
                    "payload", msg.getPayload(),
                    "at", java.time.Instant.now().toString()
                ));
            }
            logger.debug("Agent {} received bus message from {}: action={}",
                agentId, msg.getSenderId(), msg.getAction());

            // Handle REQUEST messages
            if (msg.getType() == com.nousresearch.hermes.collaboration.AgentMessage.Type.REQUEST) {
                String action = msg.getAction();
                if ("intent_subtask".equals(action)) {
                    // Actually process the subtask using the agent's model and tools
                    handleIntentSubtask(msg);
                } else {
                    // Default ack for other actions
                    sendBusReply(msg, "ack", Map.of(
                        "received", true,
                        "from", agentId,
                        "original_action", action
                    ));
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to handle bus message for {}: {}", agentId, e.getMessage());
            try {
                sendBusReply(msg, "error", Map.of("error", e.getMessage()));
            } catch (Exception ignored) {}
        }
    }

    /**
     * Handle an intent subtask request from the orchestrator.
     * Extracts the task description from the payload, processes it through
     * the agent's normal message loop, and sends the result back.
     */
    private void handleIntentSubtask(com.nousresearch.hermes.collaboration.AgentMessage msg) {
        var payload = msg.getPayload();
        Object subtaskObj = payload != null ? payload.get("subtask") : null;
        String subtask = subtaskObj != null ? String.valueOf(subtaskObj) : "unknown task";

        logger.info("Agent {} processing intent subtask: {}", agentId, subtask);

        try {
            // Build the task prompt including role context
            StringBuilder taskBuilder = new StringBuilder();
            taskBuilder.append("Role: ").append(agentRole.getRoleName()).append("\n");
            taskBuilder.append("Responsibilities: ").append(String.join(", ", agentRole.getResponsibilities())).append("\n");
            taskBuilder.append("Task: ").append(subtask).append("\n\n");
            if (payload != null && payload.get("matched_skills") != null) {
                taskBuilder.append("Relevant skills: ").append(payload.get("matched_skills")).append("\n");
            }
            taskBuilder.append("Please complete this task to the best of your ability. ");
            taskBuilder.append("Provide a clear result summary.");

            String result = processMessage(taskBuilder.toString());

            // Send the result back
            sendBusReply(msg, "subtask_result", Map.of(
                "result", result,
                "subtask", subtask,
                "status", "completed"
            ));

            logger.info("Agent {} completed subtask: {}", agentId, subtask);
        } catch (ToolApprovalRequiredException e) {
            logger.info("Agent {} subtask '{}' requires tool approval: {} ({})",
                agentId, subtask, e.getToolName(), e.getReason());
            sendBusReply(msg, "subtask_approval_required", Map.of(
                "error", e.getMessage(),
                "subtask", subtask,
                "status", "approval_required",
                "toolName", e.getToolName(),
                "toolArguments", e.getToolArguments(),
                "matchedRule", e.getMatchedRule(),
                "reason", e.getReason()
            ));
        } catch (Exception e) {
            logger.error("Agent {} failed to process subtask '{}': {}", agentId, subtask, e.getMessage());
            sendBusReply(msg, "subtask_failed", Map.of(
                "error", e.getMessage(),
                "subtask", subtask,
                "status", "failed"
            ));
        }
    }

    private void sendBusReply(com.nousresearch.hermes.collaboration.AgentMessage request,
                               String action, Map<String, Object> payload) {
        if (tenantContext == null) return;
        var reply = com.nousresearch.hermes.collaboration.AgentMessage.builder(
                agentId, request.getSenderId(),
                com.nousresearch.hermes.collaboration.AgentMessage.Type.RESPONSE)
            .action(action)
            .payload(payload)
            .replyTo(request.getMessageId())
            .build();
        reply.setResultText(payload != null && payload.get("result") != null
            ? String.valueOf(payload.get("result"))
            : null);
        tenantContext.getTenantBus().reply(request, reply);
    }

    // ==================== Helper Methods ====================

    private void initPromptContextBuilder() {
        com.nousresearch.hermes.config.ConfigManager cfg =
            com.nousresearch.hermes.config.ConfigManager.getInstance();
        this.smartMemoryCardEnabled = cfg.getBoolean("memory.smart_card.enabled", true);
        if (smartMemoryCardEnabled) {
            int topK = cfg.getInt("memory.smart_card.top_k", 6);
            boolean alwaysProfile = cfg.getBoolean("memory.smart_card.always_include_profile", true);
            this.memoryCardIntegrator =
                new com.nousresearch.hermes.memory.PromptContextBuilder(memoryManager, topK, alwaysProfile);
        }
    }

    private void initializeLearningComponents() {
        this.trajectoryCollector = new com.nousresearch.hermes.trajectory.TrajectoryCollector(tenantId);
        var skillManager = tenantContext != null
            ? new com.nousresearch.hermes.skills.SkillManager()
            : new com.nousresearch.hermes.skills.SkillManager();
        this.learningPipeline = new com.nousresearch.hermes.learning.LearningPipeline(
            memoryManager, skillManager, modelClient, trajectoryCollector);
        this.reflectionEngine = new ReflectionEngine(modelClient, memoryManager);
        // migrated into LearningPipeline
            // migrated into LearningPipeline
        this.confidenceCalibrator = new ConfidenceCalibrator();

        try {
            var cfgMgr = com.nousresearch.hermes.config.ConfigManager.getInstance();
            if (cfgMgr != null) {
                this.memoryNudgeInterval = cfgMgr.getInt("memory.nudge_interval", 10);
                this.skillNudgeInterval = cfgMgr.getInt("skills.creation_nudge_interval", 10);
            }
        } catch (Exception e) {
            logger.debug("Failed to load nudge config, using defaults");
        }
    }

    private void initializeTools() {
        com.nousresearch.hermes.tools.ToolInitializerV2.initializeAll(
            ToolRegistry.getInstance(), null);
    }

    public List<com.nousresearch.hermes.model.ToolDefinition> buildToolDefinitions() {
        return toolGateway.buildToolDefinitions();
    }

    public String buildSystemPrompt() {
        if (customSystemPrompt != null && !customSystemPrompt.isBlank()) {
            return customSystemPrompt;
        }

        // Use declarative assembler (P0-1) - lazy init on first call
        if (promptAssembler == null) {
            initPromptAssembler();
        }
        var ctx = new com.nousresearch.hermes.harness.prompt.PromptAssembleContext(
            tenantId, sessionId, agentId,
            agentRole != null ? agentRole.getRoleName() : null);
        var assembly = promptAssembler.assemble(ctx);
        String prompt = assembly.renderSystemPrompt();

        // Append dynamic contexts (memory snapshot, tool hints, evolution, team)
        // These are registered as PromptContexts when available, but during
        // migration we still append them here for backward compatibility.
        String dynamicContext = buildDynamicPromptContext();
        if (!dynamicContext.isEmpty()) {
            prompt = prompt + "\n\n" + dynamicContext;
        }

        return prompt;
    }

    /**
     * Initialize the declarative prompt assembler with default sections.
     */
    private void initPromptAssembler() {
        promptAssembler = new com.nousresearch.hermes.harness.prompt.SystemPromptAssembler();
        promptDisposer = com.nousresearch.hermes.harness.prompt.DefaultPromptSections.registerAll(promptAssembler);
        logger.debug("Initialized SystemPromptAssembler for agent {}", agentId);
    }

    /**
     * Build dynamic prompt context from memory, tool hints, evolution, and team.
     * This will be migrated to PromptContext registrations in a follow-up step.
     */
    private String buildDynamicPromptContext() {
        StringBuilder sb = new StringBuilder();

        String memoryContext = memoryManager.getSystemPromptSnapshot();
        if (!memoryContext.isEmpty()) {
            sb.append(memoryContext).append("\n\n");
        }

        if (toolPerformanceTracker != null) {
            String hints = toolPerformanceTracker.buildHintBlock();
            if (!hints.isEmpty()) {
                sb.append(hints).append("\n");
            }
        }

        if (evolutionEngine != null) {
            String evolutionCtx = evolutionEngine.buildEvolutionPrompt(agentId);
            if (!evolutionCtx.isBlank() && !evolutionCtx.trim().equals("# Self-Evolution Context")) {
                sb.append(evolutionCtx).append("\n");
            }
        }

        if (team != null) {
            String teamCtx = buildTeamAwarePrompt();
            if (!teamCtx.isEmpty()) {
                sb.append(teamCtx).append("\n");
            }
        }

        return sb.toString().trim();
    }

    public void autoSaveSession() {
        sessionLifecycle.autoSaveSession();
    }

    public void persistSession() {
        sessionLifecycle.persistSession();
    }

    private void ensureAutoSkillsLoaded(String channelId) {
        if (autoSkillsLoaded) {
            return;
        }
        synchronized (this) {
            if (autoSkillsLoaded) {
                return;
            }
            loadAutoSkills(channelId);
            autoSkillsLoaded = true;
        }
    }

    private String buildAutoLoadedSkillPrompt(com.nousresearch.hermes.tenant.core.TenantSkill skill) {
        StringBuilder skillPrompt = new StringBuilder();
        skillPrompt.append("=== AUTO-LOADED TENANT SKILL: ").append(skill.name()).append(" ===\n\n");
        if (skill.description() != null && !skill.description().isBlank()) {
            skillPrompt.append("Description: ").append(skill.description()).append("\n\n");
        }
        skillPrompt.append(skill.content() != null ? skill.content() : "");
        skillPrompt.append("\n\n=== END TENANT SKILL ===");
        return skillPrompt.toString();
    }

    private void loadAutoSkills(String channelId) {
        try {
            // 从租户配置读取自动加载的技能列表
            List<String> autoSkills = tenantContext.getConfig()
                .getStringList("skills.auto_load");

            if (autoSkills.isEmpty()) {
                logger.debug("No auto-skills configured for tenant: {}", tenantId);
                return;
            }

            TenantSkillManager skillManager = tenantContext.getSkillManager();
            int loaded = 0;

            if (conversationHistory.isEmpty()) {
                conversationHistory.add(ModelMessage.system(buildSystemPrompt()));
            }

            for (String skillName : autoSkills) {
                try {
                    var skill = skillManager.loadSkill(skillName);
                    if (skill != null) {
                        conversationHistory.add(ModelMessage.system(buildAutoLoadedSkillPrompt(skill)));
                        logger.debug("Auto-loaded skill: {} for tenant: {}", skillName, tenantId);
                        loaded++;
                    }
                } catch (Exception e) {
                    logger.warn("Failed to auto-load skill '{}' for tenant: {}", skillName, tenantId, e);
                }
            }

            if (loaded > 0) {
                logger.info("Auto-loaded {} skills for tenant: {}", loaded, tenantId);
            }

        } catch (Exception e) {
            logger.error("Failed to load auto-skills for tenant: {}", tenantId, e);
        }
    }

    private boolean handleCommand(String command) {
        String cmd = command.toLowerCase().trim();

        return switch (cmd) {
            case "/quit", "/exit", "exit", "quit" -> true;
            case "/help" -> {
                printHelp();
                yield false;
            }
            default -> {
                System.out.println("Unknown command: " + cmd);
                yield false;
            }
        };
    }

    private void printHelp() {
        System.out.println("Commands:");
        System.out.println("  exit, /quit    - Quit the agent");
        System.out.println("  /help          - Show this help");
        System.out.println("  /config        - Show configuration");
        System.out.println("  /status        - Show agent status");
    }

    public void spawnBackgroundReview(List<ModelMessage> messages,
                                        boolean reviewMemory, boolean reviewSkills) {
        sessionLifecycle.spawnBackgroundReview(messages, reviewMemory, reviewSkills);
    }

    private static String resolveTenantId(String platform, String channelId, String userId) {
        if (userId != null && !userId.isEmpty()) {
            return platform + "_" + userId.replaceAll("[^a-zA-Z0-9_-]", "_");
        }
        return "default";
    }

    private static TenantProvisioningRequest createDefaultRequest() {
        return TenantProvisioningRequest.builder("default", "system")
            .build();
    }

    private static String padRight(String s, int n) {
        return String.format("%-" + n + "s", s);
    }

    // ==================== Getters ====================

    public String getTenantId() { return tenantId; }
    public String getSessionId() { return sessionId; }
    public TenantContext getTenantContext() { return tenantContext; }

    // ======== Harness accessors (for LoopExecutor) ========

    public List<ModelMessage> getConversationHistory() { return conversationHistory; }
    public com.nousresearch.hermes.agent.IterationBudget getIterationBudget() { return iterationBudget; }
    public com.nousresearch.hermes.model.ModelClient getModelClient() { return modelClient; }
    public com.nousresearch.hermes.tools.TenantAwareToolDispatcher getToolDispatcher() { return toolDispatcher; }
    public boolean isInterrupted() { return interrupted.get(); }

    // ======== Package-private accessors for extracted subsystems ========

    HermesConfig getConfig() { return config; }
    com.nousresearch.hermes.org.observe.AgentTrace getCurrentTrace() { return currentTrace; }
    com.nousresearch.hermes.tools.ToolPerformanceTracker getToolPerformanceTracker() { return toolPerformanceTracker; }
    com.nousresearch.hermes.memory.MemoryManager getMemoryManager() { return memoryManager; }
    com.nousresearch.hermes.learning.LearningPipeline getLearningPipeline() { return learningPipeline; }
    ReflectionEngine getReflectionEngine() { return reflectionEngine; }
    com.nousresearch.hermes.trajectory.TrajectoryCollector getTrajectoryCollector() { return trajectoryCollector; }
    OrgHealthChecker getOrgHealthChecker() { return orgHealthChecker; }
    double getLastTaskScore() { return lastTaskScore; }
    void setLastTaskScore(double score) { this.lastTaskScore = score; }
    void setUserTurnCount(int count) { this.userTurnCount = count; }
    java.util.concurrent.ConcurrentLinkedQueue<String> getPendingReviewSummaries() { return pendingReviewSummaries; }
    void setApprovalSystem(ApprovalSystem system) { this.approvalSystem = system; }
    void setApprovalMessageHandler(ApprovalMessageHandler handler) { this.approvalMessageHandler = handler; }

    /** EventEmitter for structured events. Set by AgentHarness when wrapping. */
    private volatile com.nousresearch.hermes.harness.EventEmitter eventEmitter;
    public com.nousresearch.hermes.harness.EventEmitter getEventEmitter() { return eventEmitter; }
    public void setEventEmitter(com.nousresearch.hermes.harness.EventEmitter emitter) { this.eventEmitter = emitter; }

    // ===== P3-2: Agent-level maintenance =====

    public com.nousresearch.hermes.harness.maintenance.MaintenanceScheduler maintenanceScheduler() {
        return maintenanceScheduler;
    }

    /**
     * Interrupt any running maintenance (new message arrived).
     */
    public void interruptMaintenance() {
        maintenanceScheduler.interrupt();
    }

    /**
     * Run maintenance jobs in a virtual thread after response is sent.
     * Uses the latest AgentContext for job execution.
     */
    public void runMaintenance(com.nousresearch.hermes.harness.AgentContext ctx) {
        this.latestContext = ctx;
        if (maintenanceScheduler.jobs().isEmpty()) {
            maintenanceScheduler.register(
                new com.nousresearch.hermes.harness.maintenance.CompactionMaintenanceJob(ctx));
            maintenanceScheduler.register(
                new com.nousresearch.hermes.harness.maintenance.MemoryDecayMaintenanceJob(ctx));
            maintenanceScheduler.register(
                new com.nousresearch.hermes.harness.maintenance.SkillIndexRefreshJob(ctx));
            maintenanceScheduler.register(
                new com.nousresearch.hermes.harness.maintenance.SessionTitleJob(ctx));
        }
        if (maintenanceScheduler.isRunning()) return;

        Thread.startVirtualThread(() -> {
            try {
                maintenanceScheduler.runAll();
            } catch (Exception e) {
                // Silent - maintenance failures shouldn't affect user
            }
        });
    }

    // ======== Accessors for LoopExecutor preLoop/postLoop ========

    public void incrementTurnsSinceMemory() { turnsSinceMemory++; }
    public int getTurnsSinceMemory() { return turnsSinceMemory; }
    public void resetTurnsSinceMemory() { turnsSinceMemory = 0; }
    public int getItersSinceSkill() { return itersSinceSkill; }
    public void resetItersSinceSkill() { itersSinceSkill = 0; }
    public void incrementItersSinceSkill() { if (skillNudgeInterval > 0) itersSinceSkill++; }
    public void userTurnCountIncrement() { userTurnCount++; }
    public int getUserTurnCount() { return userTurnCount; }
    public int getMemoryNudgeInterval() { return memoryNudgeInterval; }
    public int getSkillNudgeInterval() { return skillNudgeInterval; }
    public boolean isSmartMemoryCardEnabled() { return smartMemoryCardEnabled; }
    public PromptContextBuilder getMemoryCardIntegrator() { return memoryCardIntegrator; }
    public CognitiveTraceCollector getCognitiveTraceCollector() { return cognitiveTraceCollector; }
    public ConfidenceCalibrator getConfidenceCalibrator() { return confidenceCalibrator; }
    public AgentEvalMetrics getEvalMetrics() { return evalMetrics; }

    // ======== AI原生组织：角色与治理 ========

    public String getAgentId() { return agentId; }
    public AgentRuntimeProfile getAgentRole() { return agentRole; }
    public GovernancePolicy getGovernancePolicy() { return governancePolicy; }

    /** 基于 config 构建默认角色 */
    private AgentRuntimeProfile buildDefaultRole() {
        String roleName = "General Assistant";
        String roleDesc = "Default general-purpose agent";
        AgentRuntimeProfile role = new AgentRuntimeProfile(roleName, roleDesc, AgentRuntimeProfile.Level.MID);
        role.allowedTools("read_file", "write_file", "search_files", "grep_files",
            "execute_command", "execute_python", "execute_bash", "execute_javascript",
            "web_search", "web_extract",
            "memory_save", "memory_get", "memory_search", "memory_delete", "memory_replace",
            "skill_list", "skill_get", "skill_search", "skill_invoke",
            "subagent_spawn", "delegate_task", "escalate_to_human",
            "find_teammate", "query_org_knowledge",
            "browser_open", "browser_navigate", "browser_screenshot", "browser_snapshot",
            "browser_click", "browser_type", "browser_get_content",
            "git_status", "git_log", "git_branch",
            "cronjob_list", "cronjob_add", "cronjob_remove",
            "vision_analyze", "tts_speak",
            "mcp_list_servers", "mcp_list_tools", "mcp_call",
            "blackboard_read", "blackboard_write", "blackboard_list",
            "team_read", "team_post", "team_status",
            "org_traces", "org_anomalies", "intent_status", "orchestrate_intent");
        role.reportsTo("human_operator");
        role.minTaskScore(0.4);
        role.maxConsecutiveFailures(3);
        return role;
    }

    public HookEngine getHookEngine() {
        PluginManager pm = PluginManager.getInstance();
        return pm != null ? pm.getHookEngineFacade() : null;
    }

    // ======== Tool approval, checkpoint, and resume logic delegated to ToolExecutionGateway ========

    /** Set a callback that fires whenever a tool call requires approval. */
    public void setToolApprovalCallback(java.util.function.Consumer<ToolApprovalRequiredException> callback) {
        toolGateway.setToolApprovalCallback(callback);
    }

    /** Check if this agent is currently paused waiting for tool approval. */
    public boolean isAwaitingToolApproval() {
        return toolGateway.isAwaitingToolApproval();
    }

    /** Get info about the pending tool approval (if any). */
    public ToolApprovalRequiredException getPendingToolApproval() {
        return toolGateway.getPendingToolApproval();
    }

    /**
     * Resume execution after a tool approval decision has been made.
     *
     * <p>If approved, executes the pending tool call normally and continues the
     * conversation loop. If rejected, injects a "tool call rejected" error as the
     * tool result and continues (the LLM will see the rejection and adjust).</p>
     *
     * @param toolCallId ID of the tool call that was pending approval
     * @param approved true if approved, false if rejected
     * @param reason reason for the decision (shown to LLM if rejected)
     * @return final response from the agent after continuing execution
     * @throws IllegalStateException if no approval is pending
     */
    public String resumeToolApproval(String toolCallId, boolean approved, String reason) {
        return toolGateway.resumeToolApproval(toolCallId, approved, reason);
    }

    /**
     * Exception thrown when a tool call requires approval.
     * Propagates up from the agent to the orchestrator/run watcher so a business
     * approval can be created and execution can be halted.
     */
    public static class ToolApprovalRequiredException extends RuntimeException {
        private final String toolName;
        private final String toolArguments;
        private final String agentId;
        private final String matchedRule;
        private final String reason;

        public ToolApprovalRequiredException(String toolName, String toolArguments,
                                              String agentId, String matchedRule, String reason) {
            super("Tool approval required: " + toolName + " - " + reason);
            this.toolName = toolName;
            this.toolArguments = toolArguments;
            this.agentId = agentId;
            this.matchedRule = matchedRule;
            this.reason = reason;
        }

        public String getToolName() { return toolName; }
        public String getToolArguments() { return toolArguments; }
        public String getAgentId() { return agentId; }
        public String getMatchedRule() { return matchedRule; }
        public String getReason() { return reason; }
    }
}
