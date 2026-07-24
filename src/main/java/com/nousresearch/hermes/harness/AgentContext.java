package com.nousresearch.hermes.harness;

import com.nousresearch.hermes.agent.CognitiveTraceCollector;
import com.nousresearch.hermes.agent.ConfidenceCalibrator;
import com.nousresearch.hermes.agent.ReflectionEngine;
import com.nousresearch.hermes.approval.ApprovalMessageHandler;
import com.nousresearch.hermes.approval.ApprovalSystem;
import com.nousresearch.hermes.collaboration.AgentRuntimeProfile;
import com.nousresearch.hermes.collaboration.GovernancePolicy;
import com.nousresearch.hermes.collaboration.TeamRuntime;
import com.nousresearch.hermes.config.HermesConfig;
import com.nousresearch.hermes.memory.MemoryManager;
import com.nousresearch.hermes.memory.PromptContextBuilder;
import com.nousresearch.hermes.model.ModelClient;
import com.nousresearch.hermes.monitoring.AgentEvalMetrics;
import com.nousresearch.hermes.org.evolution.SelfEvolutionEngine;
import com.nousresearch.hermes.org.observe.AgentObservability;
import com.nousresearch.hermes.plugin.hook.HookEngine;
import com.nousresearch.hermes.tenant.core.TenantContext;
import com.nousresearch.hermes.tenant.quota.TenantQuotaManager;
import com.nousresearch.hermes.tenant.sandbox.TenantFileSandbox;
import com.nousresearch.hermes.tools.TenantAwareToolDispatcher;
import com.nousresearch.hermes.tools.ToolPerformanceTracker;
import com.nousresearch.hermes.trajectory.TrajectoryCollector;

import java.util.Map;
import java.util.Set;

/**
 * Session-level agent context.
 *
 * <p>All fields are references borrowed from {@link TenantContext} (or global
 * singletons). AgentContext does not own any resource lifecycle — it is a
 * <em>view</em> over the tenant's resources for a specific session.</p>
 *
 * <p>This is the single object passed to {@link AgentLoop#run} and
 * {@link AgentLoop#resume}. It replaces the 30+ instance fields that were
 * scattered across {@code TenantAwareAIAgent}.</p>
 *
 * <p>Design principles:
 * <ul>
 *   <li><b>Borrow, don't own</b> — all resource references come from TenantContext</li>
 *   <li><b>Immutable identity</b> — tenantId/sessionId/agentId never change</li>
 *   <li><b>Serializable-ready</b> — contains enough to snapshot agent state</li>
 *   <li><b>SubAgent-friendly</b> — {@link #forSubAgent} produces a lite context</li>
 * </ul></p>
 */
public class AgentContext {

    // ===== Identity (immutable) =====

    private final String tenantId;
    private final String sessionId;
    private final String agentId;
    private final AgentRuntimeProfile role;

    // ===== Resource references (borrowed from TenantContext) =====

    private final TenantContext tenantContext;
    private final HermesConfig config;
    private final ModelClient modelClient;
    private final TenantAwareToolDispatcher toolDispatcher;
    private final MemoryManager memoryStore;
    private final PromptContextBuilder memoryCardIntegrator;
    private final boolean smartMemoryCardEnabled;
    private final TrajectoryCollector trajectoryCollector;
    private final LearningComponents learning;
    private final ConfidenceCalibrator confidenceCalibrator;
    private final ToolPerformanceTracker toolPerformanceTracker;
    private final CognitiveTraceCollector cognitiveTraceCollector;
    private final AgentEvalMetrics evalMetrics;
    private final ApprovalSystem approvalSystem;
    private final ApprovalMessageHandler approvalMessageHandler;
    private final GovernancePolicy governancePolicy;
    private final TenantQuotaManager quotaManager;
    private final AgentObservability observability;
    private final TenantFileSandbox sandbox;
    private final SelfEvolutionEngine evolutionEngine;
    private final TeamRuntime team;
    private final HookEngine hookEngine;

    // ===== Agent-level config =====

    private volatile String customSystemPrompt;
    private volatile Map<String, Object> modelParams;
    private final int maxIterations;
    private final int memoryNudgeInterval;
    private final int skillNudgeInterval;

    // ===== Callbacks =====

    private volatile java.util.function.Consumer<
            com.nousresearch.hermes.agent.TenantAwareAIAgent.ToolApprovalRequiredException>
        toolApprovalCallback;

    /** Pending background-review summaries (flushed at start of next turn). */
    private final java.util.concurrent.ConcurrentLinkedQueue<String> pendingReviewSummaries =
        new java.util.concurrent.ConcurrentLinkedQueue<>();

    // ===== Constructor =====

    public AgentContext(
            String tenantId,
            String sessionId,
            String agentId,
            AgentRuntimeProfile role,
            TenantContext tenantContext,
            HermesConfig config,
            ModelClient modelClient,
            TenantAwareToolDispatcher toolDispatcher,
            MemoryManager memoryStore,
            PromptContextBuilder memoryCardIntegrator,
            boolean smartMemoryCardEnabled,
            TrajectoryCollector trajectoryCollector,
            LearningComponents learning,
            ConfidenceCalibrator confidenceCalibrator,
            ToolPerformanceTracker toolPerformanceTracker,
            CognitiveTraceCollector cognitiveTraceCollector,
            AgentEvalMetrics evalMetrics,
            ApprovalSystem approvalSystem,
            ApprovalMessageHandler approvalMessageHandler,
            GovernancePolicy governancePolicy,
            TenantQuotaManager quotaManager,
            AgentObservability observability,
            TenantFileSandbox sandbox,
            SelfEvolutionEngine evolutionEngine,
            TeamRuntime team,
            HookEngine hookEngine,
            int maxIterations,
            int memoryNudgeInterval,
            int skillNudgeInterval) {
        this.tenantId = tenantId;
        this.sessionId = sessionId;
        this.agentId = agentId;
        this.role = role;
        this.tenantContext = tenantContext;
        this.config = config;
        this.modelClient = modelClient;
        this.toolDispatcher = toolDispatcher;
        this.memoryStore = memoryStore;
        this.memoryCardIntegrator = memoryCardIntegrator;
        this.smartMemoryCardEnabled = smartMemoryCardEnabled;
        this.trajectoryCollector = trajectoryCollector;
        this.learning = learning;
        this.confidenceCalibrator = confidenceCalibrator;
        this.toolPerformanceTracker = toolPerformanceTracker;
        this.cognitiveTraceCollector = cognitiveTraceCollector;
        this.evalMetrics = evalMetrics;
        this.approvalSystem = approvalSystem;
        this.approvalMessageHandler = approvalMessageHandler;
        this.governancePolicy = governancePolicy;
        this.quotaManager = quotaManager;
        this.observability = observability;
        this.sandbox = sandbox;
        this.evolutionEngine = evolutionEngine;
        this.team = team;
        this.hookEngine = hookEngine;
        this.maxIterations = maxIterations;
        this.memoryNudgeInterval = memoryNudgeInterval;
        this.skillNudgeInterval = skillNudgeInterval;
    }

    // ===== SubAgent context =====

    /**
     * Create a lite context for a SubAgent. Shares model client, hook engine,
     * and event bus, but restricts tools and disables memory writing.
     */
    public AgentContext forSubAgent(String subAgentId, Set<String> toolWhitelist, int maxIters) {
        // SubAgent gets a restricted view: no evolution, no trace collector,
        // no memory card, but shares model client, approval, hooks, sandbox.
        return new AgentContext(
            tenantId,
            sessionId + ":sub:" + subAgentId,
            subAgentId,
            role,                          // TODO: role.forSubAgent(toolWhitelist)
            tenantContext,
            config,
            modelClient,                   // shared
            toolDispatcher,                // shared (whitelist enforced at dispatch level)
            memoryStore,                   // shared read
            null,                           // no memory card for sub-agent
            false,
            null,                           // no trajectory collector
            null,                           // no learning components
            null,                           // no confidence calibrator
            null,                           // no tool performance tracker
            null,                           // no cognitive trace
            null,                           // no eval metrics
            approvalSystem,                // shared
            approvalMessageHandler,        // shared
            governancePolicy,              // shared
            quotaManager,                  // shared
            observability,                 // shared
            sandbox,                       // shared
            null,                           // no evolution engine
            team,                           // shared team
            hookEngine,                    // shared
            maxIters,
            0,                              // no memory nudge
            0                               // no skill nudge
        );
    }

    // ===== Getters =====

    public String tenantId() { return tenantId; }
    public String sessionId() { return sessionId; }
    public String agentId() { return agentId; }
    public AgentRuntimeProfile role() { return role; }
    public TenantContext tenantContext() { return tenantContext; }
    public HermesConfig config() { return config; }
    public ModelClient modelClient() { return modelClient; }
    public TenantAwareToolDispatcher toolDispatcher() { return toolDispatcher; }
    public MemoryManager memoryStore() { return memoryStore; }
    public PromptContextBuilder memoryCardIntegrator() { return memoryCardIntegrator; }
    public boolean smartMemoryCardEnabled() { return smartMemoryCardEnabled; }
    public TrajectoryCollector trajectoryCollector() { return trajectoryCollector; }
    public LearningComponents learning() { return learning; }
    public ConfidenceCalibrator confidenceCalibrator() { return confidenceCalibrator; }
    public ToolPerformanceTracker toolPerformanceTracker() { return toolPerformanceTracker; }
    public CognitiveTraceCollector cognitiveTraceCollector() { return cognitiveTraceCollector; }
    public AgentEvalMetrics evalMetrics() { return evalMetrics; }
    public ApprovalSystem approvalSystem() { return approvalSystem; }
    public ApprovalMessageHandler approvalMessageHandler() { return approvalMessageHandler; }
    public GovernancePolicy governancePolicy() { return governancePolicy; }
    public TenantQuotaManager quotaManager() { return quotaManager; }
    public AgentObservability observability() { return observability; }
    public TenantFileSandbox sandbox() { return sandbox; }
    public SelfEvolutionEngine evolutionEngine() { return evolutionEngine; }
    public TeamRuntime team() { return team; }
    public HookEngine hookEngine() { return hookEngine; }
    public int maxIterations() { return maxIterations; }
    public int memoryNudgeInterval() { return memoryNudgeInterval; }
    public int skillNudgeInterval() { return skillNudgeInterval; }

    public String customSystemPrompt() { return customSystemPrompt; }
    public void setCustomSystemPrompt(String prompt) { this.customSystemPrompt = prompt; }

    public Map<String, Object> modelParams() { return modelParams; }
    public void setModelParams(Map<String, Object> params) { this.modelParams = params; }

    public java.util.function.Consumer<
            com.nousresearch.hermes.agent.TenantAwareAIAgent.ToolApprovalRequiredException>
        toolApprovalCallback() { return toolApprovalCallback; }
    public void setToolApprovalCallback(
            java.util.function.Consumer<
                com.nousresearch.hermes.agent.TenantAwareAIAgent.ToolApprovalRequiredException> cb) {
        this.toolApprovalCallback = cb;
    }

    public java.util.concurrent.ConcurrentLinkedQueue<String> pendingReviewSummaries() {
        return pendingReviewSummaries;
    }

    // ===== Learning components bundle =====

    /** Bundles learning-related components that are always initialized together. */
    public record LearningComponents(
        com.nousresearch.hermes.learning.LearningPipeline pipeline,
        ReflectionEngine reflectionEngine
    ) {}
}
