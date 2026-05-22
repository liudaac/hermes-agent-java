package com.nousresearch.hermes.agent;

import com.nousresearch.hermes.config.HermesConfig;
import com.nousresearch.hermes.model.ModelMessage;
import com.nousresearch.hermes.model.ToolCall;
import com.nousresearch.hermes.tenant.core.*;
import com.nousresearch.hermes.tenant.quota.QuotaExceededException;
import com.nousresearch.hermes.tools.TenantAwareToolDispatcher;
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
    private final HermesConfig config;

    // 核心组件（复用现有逻辑）
    private final com.nousresearch.hermes.model.ModelClient modelClient;
    private final IterationBudget iterationBudget;
    private final List<ModelMessage> conversationHistory;
    private final AtomicBoolean interrupted;

    // 租户隔离的子组件
    private final com.nousresearch.hermes.memory.MemoryManager memoryManager;
    private com.nousresearch.hermes.trajectory.TrajectoryCollector trajectoryCollector;
    private com.nousresearch.hermes.learning.KnowledgeExtractor knowledgeExtractor;

    // Nudge intervals
    private int memoryNudgeInterval = 10;
    private int skillNudgeInterval = 10;
    private int turnsSinceMemory = 0;
    private int itersSinceSkill = 0;
    private int userTurnCount = 0;

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
        }

        // 初始化核心组件
        this.modelClient = new com.nousresearch.hermes.model.ModelClient(config.getModelConfig());
        this.iterationBudget = new IterationBudget(config.getMaxTurns());
        this.memoryManager = new com.nousresearch.hermes.memory.MemoryManager();
        this.conversationHistory = new ArrayList<>();
        this.interrupted = new AtomicBoolean(false);

        // 初始化学习组件
        initializeLearningComponents();

        // 初始化工具
        initializeTools();

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

        // 4. 执行核心处理逻辑
        return doProcessMessage(message);
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
            } catch (Exception e) {
                logger.error("Knowledge extraction failed: {}", e.getMessage());
            }
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

        loadAutoSkills("cli");

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

        StringBuilder responseBuilder = new StringBuilder();
        boolean continueLoop = true;

        while (continueLoop && !interrupted.get() && iterationBudget.hasRemaining()) {
            if (!iterationBudget.consume()) {
                responseBuilder.append("\n[Reached maximum iterations]");
                break;
            }

            try {
                var response = modelClient.chatCompletion(
                    conversationHistory,
                    buildToolDefinitions(),
                    false
                );

                ModelMessage assistantMessage = response.getMessage();
                if (assistantMessage == null) {
                    responseBuilder.append("\n[No response from model]");
                    break;
                }

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
                        String result = executeToolCall(toolCall);
                        conversationHistory.add(ModelMessage.tool(result, toolCall.getId()));
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
        if (!finalResponse.isEmpty() && !interrupted.get() &&
            (shouldReviewMemory || shouldReviewSkills)) {
            spawnBackgroundReview(new ArrayList<>(conversationHistory), shouldReviewMemory, shouldReviewSkills);
        }

        return finalResponse;
    }

    private void doProcessMessageStream(String message, java.util.function.Consumer<String> chunkConsumer) {
        userTurnCount++;

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

            try {
                var response = modelClient.chatCompletion(
                    conversationHistory,
                    buildToolDefinitions(),
                    true
                );

                ModelMessage assistantMessage = response.getMessage();
                if (assistantMessage == null) {
                    chunkConsumer.accept("\n[No response from model]");
                    break;
                }

                conversationHistory.add(assistantMessage);
                autoSaveSession();

                String content = assistantMessage.getContent();
                if (content != null && !content.isEmpty()) {
                    chunkConsumer.accept(content);
                }

                if (response.hasToolCalls()) {
                    for (ToolCall toolCall : assistantMessage.getToolCalls()) {
                        chunkConsumer.accept("\n[Executing tool: " + toolCall.getFunction().getName() + "]\n");
                        String result = executeToolCall(toolCall);
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

    // ==================== Tool Execution ====================

    private String executeToolCall(ToolCall toolCall) {
        String toolName = toolCall.getFunction().getName();
        String arguments = toolCall.getFunction().getArguments();

        logger.debug("Executing tool: {} for tenant: {}", toolName, tenantId);

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> args = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(arguments, Map.class);

            // Use TenantAwareToolDispatcher for proper sandbox isolation
            if (tenantContext != null) {
                TenantAwareToolDispatcher dispatcher = new TenantAwareToolDispatcher(
                    tenantContext, ToolRegistry.getInstance());
                return dispatcher.dispatch(toolName, args);
            } else {
                // Fallback to global registry (non-tenant mode)
                var entry = ToolRegistry.getInstance().getAllTools().stream()
                    .filter(t -> t.getName().equals(toolName))
                    .findFirst()
                    .orElse(null);
                if (entry != null) {
                    return entry.getHandler().apply(args);
                }
                return ToolRegistry.toolError("Unknown tool: " + toolName);
            }

        } catch (Exception e) {
            logger.error("Tool execution failed: {}", toolName, e);
            return ToolRegistry.toolError("Execution failed: " + e.getMessage());
        }
    }

    // Tool execution is now handled by TenantAwareToolDispatcher
    // This ensures proper sandbox isolation and permission checks

    // ==================== Helper Methods ====================

    private void initializeLearningComponents() {
        this.trajectoryCollector = new com.nousresearch.hermes.trajectory.TrajectoryCollector();
        var skillManager = tenantContext != null
            ? new com.nousresearch.hermes.skills.SkillManager()
            : new com.nousresearch.hermes.skills.SkillManager();
        this.knowledgeExtractor = new com.nousresearch.hermes.learning.KnowledgeExtractor(
            memoryManager, skillManager);

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

        return prompt.toString();
    }

    private void autoSaveSession() {
        if (conversationHistory.size() % AUTO_SAVE_INTERVAL == 0) {
            persistSession();
        }
    }

    private void persistSession() {
        try {
            var session = new com.nousresearch.hermes.gateway.SessionManager(
                com.nousresearch.hermes.config.Constants.getHermesHome())
                .getSession(sessionId);

            for (ModelMessage msg : conversationHistory) {
                if (msg.getRole() != null && msg.getContent() != null) {
                    session.addMessage(msg.getRole(), msg.getContent());
                }
            }

            new com.nousresearch.hermes.gateway.SessionManager(
                com.nousresearch.hermes.config.Constants.getHermesHome())
                .saveSession(session);

        } catch (Exception e) {
            logger.warn("Failed to save session: {}", e.getMessage());
        }
    }

    private void loadAutoSkills(String channelId) {
        // TODO: Load auto-skills from tenant context or global config
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
        // TODO: Implement background review
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
}
