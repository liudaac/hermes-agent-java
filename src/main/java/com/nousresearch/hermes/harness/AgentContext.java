package com.nousresearch.hermes.harness;

import com.nousresearch.hermes.agent.CognitiveTraceCollector;
import com.nousresearch.hermes.agent.ConfidenceCalibrator;
import com.nousresearch.hermes.agent.IterationBudget;
import com.nousresearch.hermes.agent.TenantAwareAIAgent;
import com.nousresearch.hermes.collaboration.AgentRuntimeProfile;
import com.nousresearch.hermes.collaboration.GovernancePolicy;
import com.nousresearch.hermes.collaboration.TenantBus;
import com.nousresearch.hermes.config.HermesConfig;
import com.nousresearch.hermes.memory.MemoryManager;
import com.nousresearch.hermes.memory.PromptContextBuilder;
import com.nousresearch.hermes.model.ModelClient;
import com.nousresearch.hermes.model.ModelMessage;
import com.nousresearch.hermes.monitoring.AgentEvalMetrics;
import com.nousresearch.hermes.plugin.hook.HookEngine;
import com.nousresearch.hermes.tenant.core.TenantContext;
import com.nousresearch.hermes.tenant.sandbox.TenantFileSandbox;

import java.util.List;
import java.util.Map;

/**
 * Session-level agent context — the single interface between
 * {@link AgentLoop} and the underlying agent.
 *
 * <p>Wraps a {@link TenantAwareAIAgent} and exposes only what the loop
 * needs. AgentLoop never touches TenantAwareAIAgent directly; everything
 * goes through AgentContext. This means:</p>
 *
 * <ul>
 *   <li>AgentLoop is testable with a mock context (no real agent needed)</li>
 *   <li>SubAgent can provide a restricted context (tool whitelist, no memory writes)</li>
 *   <li>Future agent implementations just need to satisfy this interface</li>
 * </ul>
 *
 * <p>Identity fields (tenantId, sessionId, agentId) are immutable.
 * The wrapped agent is also immutable. All mutability is on the agent
 * itself (conversationHistory, iterationBudget, etc.) — AgentContext
 * just provides access.</p>
 */
public class AgentContext {

    // ===== Identity (immutable) =====

    private final String tenantId;
    private final String sessionId;
    private final String agentId;

    // ===== Wrapped agent (the real state owner) =====

    private final TenantAwareAIAgent agent;

    // ===== Config =====

    private final HermesConfig config;
    private final AgentRuntimeProfile role;
    private final int maxIterations;
    private final int memoryNudgeInterval;
    private final int skillNudgeInterval;

    // ===== EventEmitter (injected by AgentHarness) =====

    private volatile EventEmitter eventEmitter;

    // ===== Constructor =====

    public AgentContext(TenantAwareAIAgent agent, HermesConfig config) {
        this.agent = agent;
        this.config = config;
        this.tenantId = agent.getTenantId();
        this.sessionId = agent.getSessionId();
        this.agentId = agent.getAgentId();
        this.role = agent.getAgentRole();
        this.maxIterations = config != null ? config.getMaxTurns() : 25;
        this.memoryNudgeInterval = agent.getMemoryNudgeInterval();
        this.skillNudgeInterval = agent.getSkillNudgeInterval();
    }

    // ===== Identity =====

    public String tenantId() { return tenantId; }
    public String sessionId() { return sessionId; }
    public String agentId() { return agentId; }
    public AgentRuntimeProfile role() { return role; }
    public HermesConfig config() { return config; }

    // ===== State access (delegated to agent) =====

    public List<ModelMessage> history() { return agent.getConversationHistory(); }
    public IterationBudget budget() { return agent.getIterationBudget(); }
    public ModelClient modelClient() { return agent.getModelClient(); }
    public Map<String, Object> modelParams() { return agent.getModelParams(); }
    public HookEngine hookEngine() { return agent.getHookEngine(); }
    public GovernancePolicy governancePolicy() { return agent.getGovernancePolicy(); }
    public boolean isInterrupted() { return agent.isInterrupted(); }

    // ===== Memory / trace / learning =====

    public CognitiveTraceCollector cognitiveTraceCollector() { return agent.getCognitiveTraceCollector(); }
    public ConfidenceCalibrator confidenceCalibrator() { return agent.getConfidenceCalibrator(); }
    public PromptContextBuilder memoryCardIntegrator() { return agent.getMemoryCardIntegrator(); }
    public AgentEvalMetrics evalMetrics() { return agent.getEvalMetrics(); }
    public boolean smartMemoryCardEnabled() { return agent.isSmartMemoryCardEnabled(); }

    // ===== Nudge counters =====

    public int memoryNudgeInterval() { return memoryNudgeInterval; }
    public int skillNudgeInterval() { return skillNudgeInterval; }
    public void incrementTurnsSinceMemory() { agent.incrementTurnsSinceMemory(); }
    public int getTurnsSinceMemory() { return agent.getTurnsSinceMemory(); }
    public void resetTurnsSinceMemory() { agent.resetTurnsSinceMemory(); }
    public int getItersSinceSkill() { return agent.getItersSinceSkill(); }
    public void resetItersSinceSkill() { agent.resetItersSinceSkill(); }

    // ===== Turn count =====

    public void userTurnCountIncrement() { agent.userTurnCountIncrement(); }
    public int getUserTurnCount() { return agent.getUserTurnCount(); }

    // ===== Mutating operations (delegated to agent) =====

    public String executeToolCall(com.nousresearch.hermes.model.ToolCall tc) {
        return agent.executeToolCall(tc);
    }

    public void recordModelUsage(com.nousresearch.hermes.model.ChatCompletionResponse response) {
        agent.recordModelUsageForHarness(response);
    }

    public void autoSaveSession() { agent.autoSaveSessionForHarness(); }
    public void persistSession() { agent.persistSessionForHarness(); }
    public String buildSystemPrompt() { return agent.buildSystemPromptForHarness(); }
    public List<com.nousresearch.hermes.model.ToolDefinition> buildToolDefinitions() {
        return agent.buildToolDefinitionsForHarness();
    }
    public int countToolsUsedThisTurn() { return agent.countToolsUsedThisTurnForHarness(); }
    public void spawnBackgroundReview(List<ModelMessage> history, boolean mem, boolean skills) {
        agent.spawnBackgroundReviewForHarness(history, mem, skills);
    }
    public void incrementItersSinceSkill() { agent.incrementItersSinceSkillForHarness(); }

    // ===== EventEmitter =====

    public EventEmitter eventEmitter() { return eventEmitter; }
    public void setEventEmitter(EventEmitter emitter) {
        this.eventEmitter = emitter;
        agent.setEventEmitter(emitter);
    }

    // ===== Wrapped agent (for direct access when needed) =====

    public TenantAwareAIAgent agent() { return agent; }
}
