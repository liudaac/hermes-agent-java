package com.nousresearch.hermes.agent;

import com.nousresearch.hermes.collaboration.AgentRole;
import com.nousresearch.hermes.collaboration.GovernancePolicy;
import com.nousresearch.hermes.collaboration.OrgHealthChecker;
import com.nousresearch.hermes.config.HermesConfig;
import com.nousresearch.hermes.model.ModelMessage;
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

    // 租户隔离的子组件
    private final com.nousresearch.hermes.memory.MemoryManager memoryManager;
    private com.nousresearch.hermes.memory.MemoryCardIntegrator memoryCardIntegrator;
    private boolean smartMemoryCardEnabled;
    private com.nousresearch.hermes.trajectory.TrajectoryCollector trajectoryCollector;
    private com.nousresearch.hermes.learning.KnowledgeExtractor knowledgeExtractor;
    private ReflectionEngine reflectionEngine;
    private com.nousresearch.hermes.learning.CuriosityEngine curiosityEngine;
    private ConfidenceCalibrator confidenceCalibrator;
    private com.nousresearch.hermes.tools.ToolPerformanceTracker toolPerformanceTracker;
    private CognitiveTraceCollector cognitiveTraceCollector;
    private com.nousresearch.hermes.monitoring.AgentEvalMetrics evalMetrics;

    // ======== AI原生组织：协作组件 ========
    private final String agentId;
    private final AgentRole agentRole;
    private final GovernancePolicy governancePolicy;
    private OrgHealthChecker orgHealthChecker;
    private com.nousresearch.hermes.org.evolution.SelfEvolutionEngine evolutionEngine;
    private com.nousresearch.hermes.collaboration.Team team;
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

    private static final int AUTO_SAVE_INTERVAL = 5;

    private static final String MEMORY_REVIEW_PROMPT =
        "Review the conversation above and consider saving to memory if appropriate.\n\n" +
        "Focus on:\n" +
        "1. Has the user revealed things about themselves — their persona, desires, " +
        "preferences, or personal details worth remembering?\n" +
        "2. Has the user expressed expectations about how you should behave, their work " +
        "style, or ways they want you to operate?\n\n" +
        "If something stands out, save it using the memory tool. " +
        "If nothing is worth saving, just say 'Nothing to save.' and stop.";

    private static final String SKILL_REVIEW_PROMPT =
        "Review the conversation above and consider saving or updating a skill if appropriate.\n\n" +
        "Focus on: was a non-trivial approach used to complete a task that required trial " +
        "and error, or changing course due to experiential findings along the way, or did " +
        "the user expect or desire a different method or outcome?\n\n" +
        "If a relevant skill already exists, update it with what you learned. " +
        "Otherwise, create a new skill if the approach is reusable.\n" +
        "If nothing is worth saving, just say 'Nothing to save.' and stop.";

    private static final String COMBINED_REVIEW_PROMPT =
        "Review the conversation above and consider two things:\n\n" +
        "**Memory**: Has the user revealed things about themselves — their persona, " +
        "desires, preferences, or personal details? Has the user expressed expectations " +
        "about how you should behave, their work style, or ways they want you to operate? " +
        "If so, save using the memory tool.\n\n" +
        "**Skills**: Was a non-trivial approach used to complete a task that required trial " +
        "and error, or changing course due to experiential findings along the way, or did " +
        "the user expect or desire a different method or outcome? If a relevant skill " +
        "already exists, update it. Otherwise, create a new one if the approach is reusable.\n\n" +
        "Only act if there's something genuinely worth saving. " +
        "If nothing stands out, just say 'Nothing to save.' and stop.";

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
        if (context == null) {
            throw new IllegalArgumentException("TenantContext is required");
        }
        this.tenantId = context.getTenantId();
        this.config = config != null ? config : new HermesConfig();
        this.sessionId = explicitSessionId != null ? explicitSessionId
            : "cli_" + UUID.randomUUID().toString().substring(0, 8);
        this.tenantContext = context;
        this.toolDispatcher = new TenantAwareToolDispatcher(tenantContext, ToolRegistry.getInstance());
        toolDispatcher.setNegotiator(tenantContext.getNegotiator());

        this.modelClient = new com.nousresearch.hermes.model.ModelClient(this.config.getModelConfig());
        this.iterationBudget = new IterationBudget(this.config.getMaxTurns());
        this.memoryManager = new com.nousresearch.hermes.memory.MemoryManager(tenantId);
        initMemoryCardIntegrator();
        this.toolPerformanceTracker = new com.nousresearch.hermes.tools.ToolPerformanceTracker(
            com.nousresearch.hermes.config.Constants.getHermesHome().resolve("tenants")
                .resolve(tenantId).resolve("state"));
        this.conversationHistory = new ArrayList<>();
        this.cognitiveTraceCollector = new CognitiveTraceCollector(
            this.sessionId, com.nousresearch.hermes.config.Constants.getHermesHome().resolve("tenants").resolve(tenantId).resolve("trajectory"));
        this.evalMetrics = new com.nousresearch.hermes.monitoring.AgentEvalMetrics();
        this.interrupted = new AtomicBoolean(false);

        // ======== AI原生组织：绑定角色与治理策略 ========
        this.agentId = "agent_" + UUID.randomUUID().toString().substring(0, 8);
        AgentRole existingRole = context.getAgentRole(this.agentId);
        if (existingRole != null) {
            this.agentRole = existingRole;
        } else {
            this.agentRole = buildDefaultRole();
            context.registerAgentRole(this.agentId, this.agentRole);
        }
        this.governancePolicy = context.getGovernancePolicy();
        this.orgHealthChecker = tenantContext.getOrgHealthChecker();
        logger.info("Agent {} bound to role '{}' in tenant {}",
            this.agentId, this.agentRole.getRoleName(), this.tenantId);

        initializeLearningComponents();
        initializeTools();
        initTenantApproval();
        tenantContext.initCollaboration();

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
        } else {
            // Fallback for non-tenant mode
            this.tenantContext = null;
        this.toolDispatcher = new TenantAwareToolDispatcher(tenantContext, ToolRegistry.getInstance());
        if (tenantContext != null) {
            toolDispatcher.setNegotiator(tenantContext.getNegotiator());
        }
        }

        // 初始化核心组件
        this.modelClient = new com.nousresearch.hermes.model.ModelClient(this.config.getModelConfig());
        this.iterationBudget = new IterationBudget(this.config.getMaxTurns());
        this.memoryManager = new com.nousresearch.hermes.memory.MemoryManager(tenantId);
        initMemoryCardIntegrator();
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
            AgentRole existingRole = this.tenantContext.getAgentRole(this.agentId);
            if (existingRole != null) {
                this.agentRole = existingRole;
            } else {
                this.agentRole = buildDefaultRole();
                this.tenantContext.registerAgentRole(this.agentId, this.agentRole);
            }
            this.governancePolicy = this.tenantContext.getGovernancePolicy();
            this.orgHealthChecker = this.tenantContext.getOrgHealthChecker();
            this.evolutionEngine = this.tenantContext.getEvolutionEngine();
            // ======== AI原生组织：第三刀——团队与总线注册 ========
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

        // ======== AI原生组织：第五刀——可观测性 ========
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
        logger.info("Ending session: {} (completed={})", sessionId, completed);

        // 保存轨迹
        if (trajectoryCollector != null) {
            trajectoryCollector.endSession(sessionId, completed);
        }

        // 提取知识
        if (knowledgeExtractor != null && completed) {
            try {
                var result = knowledgeExtractor.onSessionEnd(sessionId, conversationHistory);
                logger.info("Extracted {} insights from session", result.getInsights().size());
                if (evalMetrics != null) evalMetrics.recordKnowledgeExtraction(result.getInsights().size());
            } catch (Exception e) {
                logger.error("Knowledge extraction failed: {}", e.getMessage());
            }
        }

        // 反思 / 自我批评
        if (reflectionEngine != null && completed) {
            try {
                var rr = reflectionEngine.reflect(sessionId, conversationHistory, completed);
                if ("ok".equals(rr.status) && !rr.lessons.isEmpty()) {
                    logger.info("Reflection: score={}, lessons={}, anti_patterns={}",
                        rr.taskScore, rr.lessons.size(), rr.antiPatterns.size());
                if (evalMetrics != null) evalMetrics.recordReflection(rr.taskScore);
                }
                lastTaskScore = rr.taskScore;
            } catch (Exception e) {
                logger.error("Reflection failed: {}", e.getMessage());
            }
        }

        // 主动学习：识别弱话题并补充知识
        if (curiosityEngine != null && completed) {
            try {
                int stored = curiosityEngine.run();
                if (stored > 0) {
                    logger.info("Curiosity engine stored {} new findings", stored);
                if (evalMetrics != null) evalMetrics.recordCuriosityRun(stored);
                }
            } catch (Exception e) {
                logger.warn("Curiosity engine failed: {}", e.getMessage());
            }
        }

        // ======== AI原生组织：治理状态更新 ========
        if (completed) {
            governancePolicy.recordSuccess();
            if (agentRole != null) {
                agentRole.updateMetric("sessions_completed",
                    ((Number) agentRole.getMetrics().getOrDefault("sessions_completed", 0)).intValue() + 1);
            }
        }
        agentRole.updateMetric("last_active", System.currentTimeMillis());
        agentRole.updateMetric("tokens_used_today", governancePolicy.getTokensUsed());

        // AI原生组织：组织健康检查
        if (orgHealthChecker != null) {
            orgHealthChecker.updateHealth(
                agentId, lastTaskScore,
                governancePolicy.getConsecutiveFailures(),
                governancePolicy.getTokensUsed(),
                governancePolicy.getDailyTokenBudget()
            );
        }

        // 持久化会话
        persistSession();

        // 租户上下文持久化
        if (tenantContext != null) {
            tenantContext.getSessionManager().persistAll();
        }

        if (trajectoryCollector != null) {
            trajectoryCollector.shutdown();
        }

        // Flush cognitive traces
        if (cognitiveTraceCollector != null) {
            cognitiveTraceCollector.close();
        }

        if (evalMetrics != null) {
            evalMetrics.logSnapshot();
        }
    }

    /**
     * Return debug info for the current session (usage + tool calls).
     * Called by the dashboard playground after a message round.
     */
    public Map<String, Object> getSessionDebugInfo() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("sessionId", sessionId);
        info.put("tenantId", tenantId);
        try {
            var session = new com.nousresearch.hermes.gateway.SessionManager(
                com.nousresearch.hermes.config.Constants.getHermesHome())
                .getSession(sessionId);
            if (session != null) {
                var json = session.toJson();
                info.put("usage", Map.of(
                    "promptTokens", json.has("promptTokens") ? json.get("promptTokens").asLong() : 0L,
                    "completionTokens", json.has("completionTokens") ? json.get("completionTokens").asLong() : 0L,
                    "cachedPromptTokens", json.has("cachedPromptTokens") ? json.get("cachedPromptTokens").asLong() : 0L,
                    "reasoningTokens", json.has("reasoningTokens") ? json.get("reasoningTokens").asLong() : 0L,
                    "totalTokens", json.has("totalTokens") ? json.get("totalTokens").asLong() : 0L,
                    "lastModel", json.has("lastModel") ? json.get("lastModel").asText() : null
                ));
                if (json.has("toolCalls")) {
                    var tcs = json.get("toolCalls");
                    List<Map<String, Object>> toolList = new ArrayList<>();
                    for (var tc : tcs) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("tool", tc.get("tool").asText());
                        m.put("ok", tc.get("ok").asBoolean());
                        m.put("durationMs", tc.get("durationMs").asLong());
                        m.put("timestamp", tc.get("timestamp").asLong());
                        toolList.add(m);
                    }
                    info.put("toolCalls", toolList);
                }
            }
        } catch (Exception e) {
            logger.debug("Failed to get session debug info: {}", e.getMessage());
        }
        return info;
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
        userTurnCount++;

        // --- Plugin hook: on_session_start (first user message) ---
        HookEngine hookEngine = getHookEngine();
        if (hookEngine != null && userTurnCount == 1) {
            Map<String, Object> sessionCtx = new HashMap<>();
            sessionCtx.put("session_id", sessionId);
            sessionCtx.put("tenant_id", tenantId);
            sessionCtx.put("message", message);
            hookEngine.invoke(HookType.ON_SESSION_START, sessionCtx);
        }

        // Ensure system prompt is present at the start of conversation
        if (conversationHistory.isEmpty()) {
            conversationHistory.add(ModelMessage.system(buildSystemPrompt()));
        }

        boolean shouldReviewMemory = false;
        if (memoryNudgeInterval > 0) {
            turnsSinceMemory++;
            if (turnsSinceMemory >= memoryNudgeInterval) {
                shouldReviewMemory = true;
                turnsSinceMemory = 0;
            }
        }

        conversationHistory.add(ModelMessage.user(message));

        // Cognitive trace: observe user input
        if (cognitiveTraceCollector != null) {
            cognitiveTraceCollector.observe(userTurnCount, message);
        }

        if (smartMemoryCardEnabled && memoryCardIntegrator != null) {
            int cardSize = memoryCardIntegrator.beforeTurn(conversationHistory, message);
            if (evalMetrics != null) evalMetrics.recordMemoryQuery(cardSize > 0 ? 1 : 0, cardSize);
        }
        autoSaveSession();

        StringBuilder responseBuilder = new StringBuilder();
        boolean continueLoop = true;

        while (continueLoop && !interrupted.get() && iterationBudget.hasRemaining()) {
            if (!iterationBudget.consume()) {
                responseBuilder.append("\n[Reached maximum iterations]");
                break;
            }

            // ======== AI原生组织：治理检查点 ========
            if (governancePolicy.isPaused()) {
                String pauseMsg = "⚠️ Agent paused: " + governancePolicy.getPauseReason()
                    + "\nPlease review and restart.";
                responseBuilder.append("\n").append(pauseMsg);
                break;
            }
            if (governancePolicy.isOverBudget()) {
                logger.warn("Tenant {} agent {} over token budget, using tier: {}",
                    tenantId, agentId, governancePolicy.getActiveModel());
            }

            try {
                // --- Plugin hook: pre_llm_call ---
                if (hookEngine != null) {
                    Map<String, Object> preCtx = new HashMap<>();
                    preCtx.put("messages", new ArrayList<>(conversationHistory));
                    preCtx.put("session_id", sessionId);
                    preCtx.put("tenant_id", tenantId);
                    preCtx.put("turn", userTurnCount);
                    List<Object> injects = hookEngine.invoke(HookType.PRE_LLM_CALL, preCtx);
                    for (Object inj : injects) {
                        if (inj instanceof String s && !s.isEmpty()) {
                            conversationHistory.add(ModelMessage.system(s));
                            logger.debug("Plugin injected context into conversation");
                        }
                    }
                }

                // Cognitive trace: orient — form goal/hypothesis before LLM call
                if (cognitiveTraceCollector != null) {
                    String goal = "Respond to user turn " + userTurnCount;
                    String hypothesis = "Based on conversation history, determine next action";
                    cognitiveTraceCollector.orient(userTurnCount, goal, hypothesis);
                }

                long llmStart = System.currentTimeMillis();
                var response = modelClient.chatCompletion(
                    conversationHistory,
                    buildToolDefinitions(),
                    false,
                    modelParams
                );
                long llmDuration = System.currentTimeMillis() - llmStart;

                // Cognitive trace: decide — LLM produced output
                if (cognitiveTraceCollector != null) {
                    String action = response.getMessage() != null ? response.getMessage().getContent() : "";
                    String toolUsed = response.hasToolCalls() && response.getMessage().getToolCalls() != null
                        ? response.getMessage().getToolCalls().get(0).getFunction().getName()
                        : null;
                    cognitiveTraceCollector.decide(userTurnCount, action, toolUsed, llmDuration);
                }

                // --- Plugin hook: post_llm_call ---
                if (hookEngine != null) {
                    Map<String, Object> postCtx = new HashMap<>();
                    postCtx.put("message", response.getMessage());
                    postCtx.put("finish_reason", response.getFinishReason());
                    postCtx.put("session_id", sessionId);
                    postCtx.put("tenant_id", tenantId);
                    hookEngine.invoke(HookType.POST_LLM_CALL, postCtx);
                }

                ModelMessage assistantMessage = response.getMessage();
                if (assistantMessage == null) {
                    responseBuilder.append("\n[No response from model]");
                    break;
                }

                recordModelUsage(response);

                conversationHistory.add(assistantMessage);
                autoSaveSession();

                if (response.hasToolCalls()) {
                    if (assistantMessage.getContent() != null && !assistantMessage.getContent().isEmpty()) {
                        if (responseBuilder.length() > 0) {
                            responseBuilder.append("\n\n");
                        }
                        responseBuilder.append(assistantMessage.getContent());
                    }

                    for (ToolCall toolCall : assistantMessage.getToolCalls()) {
                        long toolStart = System.currentTimeMillis();
                        boolean toolOk = true;
                        String result;
                        try {
                            result = executeToolCall(toolCall);
                        } catch (RuntimeException ex) {
                            toolOk = false;
                            throw ex;
                        } finally {
                            recordToolCall(toolCall, toolOk, System.currentTimeMillis() - toolStart);
                        }
                        conversationHistory.add(ModelMessage.tool(result, toolCall.getId()));

                        // Cognitive trace: evaluate tool result
                        if (cognitiveTraceCollector != null) {
                            cognitiveTraceCollector.evaluate(userTurnCount,
                                "Tool " + toolCall.getFunction().getName() + " returned: " + result.substring(0, Math.min(100, result.length())));
                        }
                    }

                    if (skillNudgeInterval > 0) {
                        itersSinceSkill++;
                    }

                    continueLoop = true;
                } else {
                    String content = assistantMessage.getContent();
                    if (content != null && !content.isEmpty()) {
                        if (responseBuilder.length() > 0) {
                            responseBuilder.append("\n\n");
                        }
                        responseBuilder.append(content);
                    }
                    continueLoop = false;
                }

                if ("stop".equals(response.getFinishReason())) {
                    continueLoop = false;
                }

            } catch (Exception e) {
                logger.error("Error in conversation loop: {}", e.getMessage(), e);
                responseBuilder.append("\n[Error: ").append(e.getMessage()).append("]");
                break;
            }
        }

        persistSession();

        boolean shouldReviewSkills = false;
        if (skillNudgeInterval > 0 && itersSinceSkill >= skillNudgeInterval) {
            shouldReviewSkills = true;
            itersSinceSkill = 0;
        }

        String finalResponse = responseBuilder.toString();

        // Calibrate confidence before returning
        if (confidenceCalibrator != null && !finalResponse.isEmpty()) {
            int toolsUsed = countToolsUsedThisTurn();
            boolean hasSearch = conversationHistory.stream()
                .anyMatch(m -> m.getContent() != null && m.getContent().contains("Search results"));
            var calibrated = confidenceCalibrator.calibrate(finalResponse, toolsUsed, hasSearch);
            if (evalMetrics != null) evalMetrics.recordCalibration(calibrated.action());
            if (calibrated.action() != ConfidenceCalibrator.Action.DIRECT) {
                finalResponse = calibrated.adjustedText();
            }
        }

        if (!finalResponse.isEmpty() && !interrupted.get() &&
            (shouldReviewMemory || shouldReviewSkills)) {
            spawnBackgroundReview(new ArrayList<>(conversationHistory), shouldReviewMemory, shouldReviewSkills);
        }

        // --- Plugin hook: transform_llm_output ---
        if (hookEngine != null && !finalResponse.isEmpty()) {
            Map<String, Object> outCtx = new HashMap<>();
            outCtx.put("text", finalResponse);
            outCtx.put("session_id", sessionId);
            outCtx.put("tenant_id", tenantId);
            List<Object> transforms = hookEngine.invoke(HookType.TRANSFORM_LLM_OUTPUT, outCtx);
            for (Object t : transforms) {
                if (t instanceof String s && !s.isEmpty()) {
                    finalResponse = s;
                    logger.debug("LLM output transformed by plugin");
                }
            }
        }

        return finalResponse;
    }

    private void doProcessMessageStream(String message, java.util.function.Consumer<String> chunkConsumer) {
        userTurnCount++;

        // Ensure system prompt is present at the start of conversation
        if (conversationHistory.isEmpty()) {
            conversationHistory.add(ModelMessage.system(buildSystemPrompt()));
        }

        boolean shouldReviewMemory = false;
        if (memoryNudgeInterval > 0) {
            turnsSinceMemory++;
            if (turnsSinceMemory >= memoryNudgeInterval) {
                shouldReviewMemory = true;
                turnsSinceMemory = 0;
            }
        }

        conversationHistory.add(ModelMessage.user(message));
        autoSaveSession();

        boolean continueLoop = true;

        while (continueLoop && !interrupted.get() && iterationBudget.hasRemaining()) {
            if (!iterationBudget.consume()) {
                chunkConsumer.accept("\n[Reached maximum iterations]");
                break;
            }

            // ======== AI原生组织：治理检查点 ========
            if (governancePolicy.isPaused()) {
                chunkConsumer.accept("\n⚠️ Agent paused: " + governancePolicy.getPauseReason() + "\n");
                break;
            }

            try {
                // --- Plugin hook: pre_llm_call (stream) ---
                HookEngine hookEngineStream = getHookEngine();
                if (hookEngineStream != null) {
                    Map<String, Object> preCtx = new HashMap<>();
                    preCtx.put("messages", new ArrayList<>(conversationHistory));
                    preCtx.put("session_id", sessionId);
                    preCtx.put("tenant_id", tenantId);
                    preCtx.put("turn", userTurnCount);
                    List<Object> injects = hookEngineStream.invoke(HookType.PRE_LLM_CALL, preCtx);
                    for (Object inj : injects) {
                        if (inj instanceof String s && !s.isEmpty()) {
                            conversationHistory.add(ModelMessage.system(s));
                        }
                    }
                }

                var response = modelClient.chatCompletion(
                    conversationHistory,
                    buildToolDefinitions(),
                    true,
                    modelParams,
                    chunk -> chunkConsumer.accept(chunk)
                );

                // --- Plugin hook: post_llm_call (stream) ---
                if (hookEngineStream != null) {
                    Map<String, Object> postCtx = new HashMap<>();
                    postCtx.put("message", response.getMessage());
                    postCtx.put("finish_reason", response.getFinishReason());
                    postCtx.put("session_id", sessionId);
                    postCtx.put("tenant_id", tenantId);
                    hookEngineStream.invoke(HookType.POST_LLM_CALL, postCtx);
                }

                ModelMessage assistantMessage = response.getMessage();
                if (assistantMessage == null) {
                    chunkConsumer.accept("\n[No response from model]");
                    break;
                }

                recordModelUsage(response);

                conversationHistory.add(assistantMessage);
                autoSaveSession();

                if (response.hasToolCalls()) {
                    for (ToolCall toolCall : assistantMessage.getToolCalls()) {
                        chunkConsumer.accept("\n[Executing tool: " + toolCall.getFunction().getName() + "]\n");
                        long toolStart = System.currentTimeMillis();
                        boolean toolOk = true;
                        String result;
                        try {
                            result = executeToolCall(toolCall);
                        } catch (RuntimeException ex) {
                            toolOk = false;
                            throw ex;
                        } finally {
                            recordToolCall(toolCall, toolOk, System.currentTimeMillis() - toolStart);
                        }
                        conversationHistory.add(ModelMessage.tool(result, toolCall.getId()));
                    }

                    if (skillNudgeInterval > 0) {
                        itersSinceSkill++;
                    }

                    continueLoop = true;
                } else {
                    continueLoop = false;
                }

                if ("stop".equals(response.getFinishReason())) {
                    continueLoop = false;
                }

            } catch (Exception e) {
                logger.error("Error in stream conversation loop: {}", e.getMessage(), e);
                chunkConsumer.accept("\n[Error: " + e.getMessage() + "]");
                break;
            }
        }

        persistSession();

        boolean shouldReviewSkills = false;
        if (skillNudgeInterval > 0 && itersSinceSkill >= skillNudgeInterval) {
            shouldReviewSkills = true;
            itersSinceSkill = 0;
        }

        if (shouldReviewMemory || shouldReviewSkills) {
            spawnBackgroundReview(new ArrayList<>(conversationHistory), shouldReviewMemory, shouldReviewSkills);
        }
    }


    private void recordModelUsage(com.nousresearch.hermes.model.ChatCompletionResponse response) {
        if (response == null || response.getUsage() == null) {
            return;
        }
        try {
            var session = new com.nousresearch.hermes.gateway.SessionManager(
                com.nousresearch.hermes.config.Constants.getHermesHome())
                .getSession(sessionId);
            var usage = response.getUsage();
            session.recordUsage(
                response.getModel() != null ? response.getModel() : "unknown",
                usage.getPromptTokens(),
                usage.getCompletionTokens(),
                usage.getCachedPromptTokens(),
                usage.getReasoningTokens(),
                usage.getTotalTokens()
            );
        } catch (Exception e) {
            logger.debug("Failed to record model usage: {}", e.getMessage());
        }
    }

    private void recordToolCall(ToolCall toolCall, boolean ok, long durationMs) {
        try {
            var session = new com.nousresearch.hermes.gateway.SessionManager(
                com.nousresearch.hermes.config.Constants.getHermesHome())
                .getSession(sessionId);
            session.recordToolCall(toolCall.getFunction().getName(), ok, durationMs);
            if (toolPerformanceTracker != null) {
                toolPerformanceTracker.record(toolCall.getFunction().getName(), ok, durationMs);
            }
            if (evalMetrics != null) {
                evalMetrics.recordToolCall(ok, durationMs);
            }
        } catch (Exception e) {
            logger.debug("Failed to record tool call: {}", e.getMessage());
        }
    }

    /**
     * Count how many tool-result messages appear after the last user message.
     */
    private int countToolsUsedThisTurn() {
        int count = 0;
        for (int i = conversationHistory.size() - 1; i >= 0; i--) {
            ModelMessage m = conversationHistory.get(i);
            if ("user".equals(m.getRole())) break;
            if ("tool".equals(m.getRole())) count++;
        }
        return count;
    }

    // ==================== Approval ====================
    
    /**
     * Initialize per-tenant approval system and wire into the tool dispatcher.
     */
    private void initTenantApproval() {
        this.approvalSystem = new ApprovalSystem();
        this.approvalMessageHandler = new ApprovalMessageHandler();
        
        // Wire to the persistent tool dispatcher
        if (toolDispatcher != null) {
            toolDispatcher.setApprovalSystem(approvalSystem);
            toolDispatcher.setApprovalMessageHandler(approvalMessageHandler);
        }
        
        // Wire sub-agent shared memory callback for this tenant
        
        logger.info("Tenant approval system initialized for: {}", tenantId);
    }
    
    // ==================== Tool Execution ====================

    private String executeToolCall(ToolCall toolCall) {
        String toolName = toolCall.getFunction().getName();
        String arguments = toolCall.getFunction().getArguments();

        logger.debug("Executing tool: {} for tenant: {}", toolName, tenantId);

        // ======== AI原生组织：第五刀——可观测性记录 ========
        long toolStartMs = System.currentTimeMillis();
        if (currentTrace != null) {
            currentTrace.step(com.nousresearch.hermes.org.observe.AgentTrace.Step.toolCall(
                toolName, arguments, java.util.List.of(), 1.0, 0, 0.0));
        }

        // ======== AI原生组织：角色权限检查 ========
        if (agentRole != null && !agentRole.getAllowedTools().isEmpty()
                && !agentRole.getAllowedTools().contains(toolName)) {
            String msg = "Access denied: '" + toolName + "' not allowed for role '" + agentRole.getRoleName() + "'";
            logger.warn("Tenant {} agent {} {}", tenantId, agentId, msg);
            if (currentTrace != null) {
                currentTrace.step(com.nousresearch.hermes.org.observe.AgentTrace.Step.error(msg));
            }
            return ToolRegistry.toolError(msg);
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> args = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(arguments, Map.class);

            String result;

            // Use persistent TenantAwareToolDispatcher (approval-aware)
            if (toolDispatcher != null) {
                result = toolDispatcher.dispatch(toolName, args);
            } else {
                // Fallback to global registry (non-tenant mode)
                var entry = ToolRegistry.getInstance().getAllTools().stream()
                    .filter(t -> t.getName().equals(toolName))
                    .findFirst()
                    .orElse(null);
                if (entry != null) {
                    result = entry.getHandler().apply(args);
                } else {
                    result = ToolRegistry.toolError("Unknown tool: " + toolName);
                }
            }

            // AI原生组织：第五刀——记录工具结果到追踪
            if (currentTrace != null) {
                long duration = System.currentTimeMillis() - toolStartMs;
                currentTrace.step(com.nousresearch.hermes.org.observe.AgentTrace.Step.toolResult(
                    toolName, result, duration));
            }

            // AI原生组织：记录成功模式，强化有效策略
            if (evolutionEngine != null && !result.contains("\"error\"")) {
                evolutionEngine.recordSuccess(agentId, toolName,
                    "Tool '" + toolName + "' executed with args: " + args.keySet());
            }

            return result;

        } catch (Exception e) {
            logger.error("Tool execution failed: {}", toolName, e);

            // AI原生组织：第五刀——记录错误到追踪
            if (currentTrace != null) {
                currentTrace.step(com.nousresearch.hermes.org.observe.AgentTrace.Step.error(
                    toolName + ": " + e.getMessage()));
            }

            // AI原生组织：记录失败，驱动自我进化
            if (evolutionEngine != null) {
                var failure = new com.nousresearch.hermes.org.evolution.FailureCase.Builder(
                        agentId,
                        "Execute tool: " + toolName,
                        e.getMessage()
                    )
                    .rootCause(determineRootCause(e, toolName))
                    .severity(com.nousresearch.hermes.org.evolution.FailureCase.Severity.MEDIUM)
                    .lesson("Tool '" + toolName + "' failed: " + e.getClass().getSimpleName())
                    .build();
                evolutionEngine.recordFailure(failure);
            }

            return ToolRegistry.toolError("Execution failed: " + e.getMessage());
        }
    }

    // Tool execution is now handled by TenantAwareToolDispatcher
    // This ensures proper sandbox isolation and permission checks

    // ======== AI原生组织：失败根因分析 ========
    private static com.nousresearch.hermes.org.evolution.FailureCase.RootCause determineRootCause(
            Exception e, String toolName) {
        String msg = (e.getMessage() != null ? e.getMessage().toLowerCase() : "");
        if (msg.contains("permission") || msg.contains("denied") || msg.contains("access")) {
            return com.nousresearch.hermes.org.evolution.FailureCase.RootCause.PERMISSION_DENIED;
        }
        if (msg.contains("not found") || msg.contains("unknown") || msg.contains("no such")) {
            return com.nousresearch.hermes.org.evolution.FailureCase.RootCause.WRONG_TOOL;
        }
        if (msg.contains("timeout") || msg.contains("timed out")) {
            return com.nousresearch.hermes.org.evolution.FailureCase.RootCause.INSUFFICIENT_CONTEXT;
        }
        if (msg.contains("ambiguous") || msg.contains("unclear")) {
            return com.nousresearch.hermes.org.evolution.FailureCase.RootCause.AMBIGUOUS_PROMPT;
        }
        return com.nousresearch.hermes.org.evolution.FailureCase.RootCause.WRONG_TOOL;
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

    // ======== AI原生组织：第三刀——Team-Aware Methods ========

    /** Get the team this agent belongs to. */
    public com.nousresearch.hermes.collaboration.Team getTeam() {
        return team;
    }

    /** Set the team this agent belongs to (also adds agent to team). */
    public void setTeam(com.nousresearch.hermes.collaboration.Team team) {
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

            // Auto-reply to REQUEST messages with a default response
            if (msg.getType() == com.nousresearch.hermes.collaboration.AgentMessage.Type.REQUEST) {
                var reply = com.nousresearch.hermes.collaboration.AgentMessage.builder(
                        agentId, msg.getSenderId(), com.nousresearch.hermes.collaboration.AgentMessage.Type.RESPONSE)
                    .action("ack")
                    .payload(java.util.Map.of(
                        "received", true,
                        "from", agentId,
                        "original_action", msg.getAction()
                    ))
                    .replyTo(msg.getMessageId())
                    .build();
                // Use the bus to reply (need to get it from context)
                if (tenantContext != null) {
                    tenantContext.getTenantBus().reply(msg, reply);
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to handle bus message for {}: {}", agentId, e.getMessage());
        }
    }

    // ==================== Helper Methods ====================

    private void initMemoryCardIntegrator() {
        com.nousresearch.hermes.config.ConfigManager cfg =
            com.nousresearch.hermes.config.ConfigManager.getInstance();
        this.smartMemoryCardEnabled = cfg.getBoolean("memory.smart_card.enabled", true);
        if (smartMemoryCardEnabled) {
            int topK = cfg.getInt("memory.smart_card.top_k", 6);
            boolean alwaysProfile = cfg.getBoolean("memory.smart_card.always_include_profile", true);
            this.memoryCardIntegrator =
                new com.nousresearch.hermes.memory.MemoryCardIntegrator(memoryManager, topK, alwaysProfile);
        }
    }

    private void initializeLearningComponents() {
        this.trajectoryCollector = new com.nousresearch.hermes.trajectory.TrajectoryCollector(tenantId);
        var skillManager = tenantContext != null
            ? new com.nousresearch.hermes.skills.SkillManager()
            : new com.nousresearch.hermes.skills.SkillManager();
        this.knowledgeExtractor = new com.nousresearch.hermes.learning.KnowledgeExtractor(
            memoryManager, skillManager);
        this.reflectionEngine = new ReflectionEngine(modelClient, memoryManager);
        this.curiosityEngine = new com.nousresearch.hermes.learning.CuriosityEngine(
            modelClient, memoryManager, trajectoryCollector);
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

    private List<com.nousresearch.hermes.model.ToolDefinition> buildToolDefinitions() {
        var registry = ToolRegistry.getInstance();
        Set<String> toolNames = new HashSet<>(registry.getAllToolNames());

        // 如果处于租户模式，过滤掉不允许的工具
        if (tenantContext != null) {
            var allowed = tenantContext.getSecurityPolicy().getAllowedTools();
            var denied = tenantContext.getSecurityPolicy().getDeniedTools();

            if (!allowed.isEmpty()) {
                toolNames.retainAll(allowed);
            }
            toolNames.removeAll(denied);
        }

        // Convert Map definitions to ToolDefinition objects
        List<Map<String, Object>> defs = registry.getDefinitions(toolNames, false);
        List<com.nousresearch.hermes.model.ToolDefinition> result = new ArrayList<>();
        for (Map<String, Object> def : defs) {
            Map<String, Object> function = (Map<String, Object>) def.get("function");
            if (function != null) {
                String name = (String) function.get("name");
                String description = (String) function.get("description");
                Map<String, Object> parameters = (Map<String, Object>) function.get("parameters");
                result.add(com.nousresearch.hermes.model.ToolDefinition.builder()
                    .name(name)
                    .description(description)
                    .parameters(parameters)
                    .build());
            }
        }
        return result;
    }

    private String buildSystemPrompt() {
        if (customSystemPrompt != null && !customSystemPrompt.isBlank()) {
            return customSystemPrompt;
        }
        StringBuilder prompt = new StringBuilder();
        prompt.append(com.nousresearch.hermes.config.Constants.DEFAULT_AGENT_IDENTITY).append("\n\n");
        prompt.append(com.nousresearch.hermes.config.Constants.MEMORY_GUIDANCE).append("\n\n");
        prompt.append(com.nousresearch.hermes.config.Constants.TOOL_USE_ENFORCEMENT_GUIDANCE).append("\n\n");
        prompt.append(com.nousresearch.hermes.config.Constants.EXECUTION_DISCIPLINE_GUIDANCE).append("\n\n");
        prompt.append(com.nousresearch.hermes.config.Constants.SESSION_SEARCH_GUIDANCE).append("\n\n");
        prompt.append(com.nousresearch.hermes.config.Constants.SKILLS_GUIDANCE).append("\n\n");

        String memoryContext = memoryManager.getSystemPromptSnapshot();
        if (!memoryContext.isEmpty()) {
            prompt.append(memoryContext).append("\n\n");
        }

        // Tool performance hints (learned from past usage)
        if (toolPerformanceTracker != null) {
            String hints = toolPerformanceTracker.buildHintBlock();
            if (!hints.isEmpty()) {
                prompt.append(hints).append("\n");
            }
        }

        // ======== AI原生组织：自进化上下文 ========
        // 注入从历史失败中学到的经验教训，让 Agent 持续进化
        if (evolutionEngine != null) {
            String evolutionCtx = evolutionEngine.buildEvolutionPrompt(agentId);
            if (!evolutionCtx.isBlank() && !evolutionCtx.trim().equals("# Self-Evolution Context")) {
                prompt.append(evolutionCtx).append("\n");
            }
        }

        // ======== AI原生组织：第三刀——Team 上下文注入 ========
        // 让 Agent 知道自己属于哪个团队、有哪些队友、最近发生了什么
        if (team != null) {
            prompt.append(buildTeamAwarePrompt()).append("\n");
        }

        return prompt.toString();
    }

    private void autoSaveSession() {
        persistSession();
    }

    private void persistSession() {
        try {
            var hermesHome = com.nousresearch.hermes.config.Constants.getHermesHome();
            logger.debug("Persisting session {} to {}", sessionId, hermesHome);

            var sessionMgr = new com.nousresearch.hermes.gateway.SessionManager(hermesHome);
            var session = sessionMgr.getSession(sessionId);

            // Clear and rebuild messages to avoid duplicates
            session.messages.clear();
            for (ModelMessage msg : conversationHistory) {
                if (msg.getRole() != null && msg.getContent() != null) {
                    session.addMessage(msg.getRole(), msg.getContent());
                }
            }

            // Set source info for dashboard display
            if (session.platform == null) {
                session.platform = "web";
            }
            session.lastActivity = System.currentTimeMillis();

            sessionMgr.saveSession(session);
            logger.info("Session persisted: {} ({} messages)", sessionId, session.messages.size());

        } catch (Exception e) {
            logger.error("Failed to save session {}: {}", sessionId, e.getMessage(), e);
        }
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
            
            for (String skillName : autoSkills) {
                try {
                    var skill = skillManager.loadSkill(skillName);
                    if (skill != null) {
                        logger.debug("Auto-loaded skill: {} for tenant: {}", skillName, tenantId);
                        loaded++;
                    }
                } catch (Exception e) {
                    logger.warn("Failed to auto-load skill '{}' for tenant: {}", skillName, tenantId);
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

    private void spawnBackgroundReview(List<ModelMessage> messages,
                                        boolean reviewMemory, boolean reviewSkills) {
        // 使用虚拟线程进行后台审查，不阻塞主对话流程
        Thread.startVirtualThread(() -> {
            try {
                logger.debug("Starting background review: memory={}, skills={}", reviewMemory, reviewSkills);
                
                if (reviewMemory) {
                    reviewAndSaveMemory(messages);
                }
                
                if (reviewSkills) {
                    reviewAndSuggestSkills(messages);
                }
                
            } catch (Exception e) {
                logger.error("Background review failed", e);
            }
        });
    }
    
    /**
     * 审查对话并保存有价值的记忆
     */
    private void reviewAndSaveMemory(List<ModelMessage> messages) {
        try {
            // 提取用户信息（最后几条用户消息）
            List<String> userMessages = messages.stream()
                .filter(m -> "user".equals(m.getRole()))
                .map(ModelMessage::getContent)
                .filter(Objects::nonNull)
                .toList();
            
            if (userMessages.isEmpty()) {
                return;
            }
            
            // 检查是否有值得保存的新信息
            String lastUserMessage = userMessages.get(userMessages.size() - 1);
            
            // 简单启发式：如果消息包含偏好、事实或上下文信息，则保存
            if (containsValuableInfo(lastUserMessage)) {
                // 构建记忆摘要
                String memory = extractMemorySummary(messages);
                if (memory != null && !memory.isEmpty()) {
                    memoryManager.addUser(memory);
                    logger.debug("Saved memory from conversation: {}", 
                        memory.substring(0, Math.min(50, memory.length())));
                }
            }
            
        } catch (Exception e) {
            logger.error("Memory review failed", e);
        }
    }
    
    /**
     * 审查对话并建议技能创建
     */
    private void reviewAndSuggestSkills(List<ModelMessage> messages) {
        try {
            // 分析对话中是否使用了复杂的多步骤方法
            int toolCallCount = countToolCalls(messages);
            int turnCount = (int) messages.stream()
                .filter(m -> "user".equals(m.getRole()))
                .count();
            
            // 启发式：如果对话超过5轮且使用了工具，可能是可复用的工作流
            if (turnCount >= 3 && toolCallCount >= 2) {
                // 提取可能的技能描述
                String skillDescription = extractSkillDescription(messages);
                if (skillDescription != null) {
                    logger.info("Potential skill detected: {}", skillDescription);
                    // 保存到轨迹收集器供后续分析
                    if (trajectoryCollector != null) {
                        // 记录技能候选
                        logger.debug("Skill candidate recorded for tenant: {}", tenantId);
                    }
                }
            }
            
        } catch (Exception e) {
            logger.error("Skill review failed", e);
        }
    }
    
    /**
     * 检查消息是否包含有价值的信息
     */
    private boolean containsValuableInfo(String message) {
        if (message == null || message.length() < 10) {
            return false;
        }
        String lower = message.toLowerCase();
        // 偏好指示器
        return lower.contains("prefer") || lower.contains("like") || 
               lower.contains("always") || lower.contains("usually") ||
               lower.contains("don't") || lower.contains("never") ||
               lower.contains("my name is") || lower.contains("i am") ||
               lower.contains("remember") || lower.contains("important");
    }
    
    /**
     * 从对话中提取记忆摘要
     */
    private String extractMemorySummary(List<ModelMessage> messages) {
        // 简化实现：提取最后几条消息作为上下文
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (int i = messages.size() - 1; i >= 0 && count < 3; i--) {
            ModelMessage msg = messages.get(i);
            if ("user".equals(msg.getRole()) && msg.getContent() != null) {
                if (sb.length() > 0) sb.insert(0, "; ");
                sb.insert(0, msg.getContent());
                count++;
            }
        }
        return sb.toString();
    }
    
    /**
     * 统计工具调用次数
     */
    private int countToolCalls(List<ModelMessage> messages) {
        return (int) messages.stream()
            .filter(m -> m.getToolCalls() != null && !m.getToolCalls().isEmpty())
            .count();
    }
    
    /**
     * 从对话中提取技能描述
     */
    private String extractSkillDescription(List<ModelMessage> messages) {
        // 简化实现：检查是否有明确的任务完成模式
        for (int i = messages.size() - 1; i >= 0; i--) {
            ModelMessage msg = messages.get(i);
            if ("assistant".equals(msg.getRole()) && msg.getContent() != null) {
                String content = msg.getContent().toLowerCase();
                if (content.contains("done") || content.contains("completed") || 
                    content.contains("finished") || content.contains("here is")) {
                    return "Workflow completion pattern detected";
                }
            }
        }
        return null;
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

    // ======== AI原生组织：角色与治理 ========

    public String getAgentId() { return agentId; }
    public AgentRole getAgentRole() { return agentRole; }
    public GovernancePolicy getGovernancePolicy() { return governancePolicy; }

    /** 基于 config 构建默认角色 */
    private AgentRole buildDefaultRole() {
        String roleName = "General Assistant";
        String roleDesc = "Default general-purpose agent";
        AgentRole role = new AgentRole(roleName, roleDesc, AgentRole.Level.MID);
        role.allowedTools("read", "write", "exec", "web_search", "web_fetch",
            "memory", "skills", "session");
        role.reportsTo("human_operator");
        role.minTaskScore(0.4);
        role.maxConsecutiveFailures(3);
        return role;
    }

    private HookEngine getHookEngine() {
        PluginManager pm = PluginManager.getInstance();
        return pm != null ? pm.getHookEngineFacade() : null;
    }
}
