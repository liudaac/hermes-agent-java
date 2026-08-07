package com.nousresearch.hermes.harness;

import com.nousresearch.hermes.agent.TenantAwareAIAgent;
import com.nousresearch.hermes.collaboration.TenantBus;
import com.nousresearch.hermes.config.HermesConfig;
import com.nousresearch.hermes.tenant.core.TenantContext;
import com.nousresearch.hermes.tenant.core.TenantAIAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Session-level agent harness: wraps {@link TenantAIAgent} with
 * {@link AgentContext}, {@link LoopState}, and {@link EventEmitter}.
 *
 * <p>This is the "execution container" that the frontend interacts with
 * via /api/harness/{sessionId}/stream.</p>
 */
public class AgentHarness {
    private static final Logger logger = LoggerFactory.getLogger(AgentHarness.class);

    private final TenantContext tenantCtx;
    private final String sessionId;
    private final TenantAIAgent delegate;
    private final long startedAtMs;
    private final CheckpointStore checkpointStore;
    private final HermesConfig config;

    private volatile LoopState state;
    private volatile EventEmitter emitter;
    private volatile String currentPhase = "idle";

    public AgentHarness(TenantContext tenantCtx, String sessionId, HermesConfig config) {
        this(tenantCtx, sessionId, config, null);
    }

    public AgentHarness(TenantContext tenantCtx, String sessionId, HermesConfig config,
                        CheckpointStore checkpointStore) {
        this.tenantCtx = tenantCtx;
        this.sessionId = sessionId;
        this.startedAtMs = System.currentTimeMillis();
        this.checkpointStore = checkpointStore;
        this.config = config;

        // Try to restore from checkpoint
        int maxIters = config != null ? config.getMaxTurns() : 25;
        LoopState restored = checkpointStore != null ? checkpointStore.load(sessionId, maxIters) : null;
        this.state = restored != null ? restored : new LoopState(maxIters);

        // Create delegate agent
        this.delegate = config != null
            ? new TenantAIAgent(tenantCtx, sessionId, config)
            : new TenantAIAgent(tenantCtx, sessionId);

        // Create emitter (TenantBus may be null in tests)
        TenantBus bus = null;
        try {
            bus = tenantCtx.getTenantBus();
        } catch (Exception ignored) {}
        this.emitter = new EventEmitter(
            tenantCtx.getTenantId(), sessionId, delegate.getSessionId(), bus
        );

        // Wire emitter into the underlying agent so AgentLoop.run() events flow through
        delegate.setEventEmitter(this.emitter);
    }

    /** Process a user message through the harness (AgentLoop + structured events). */
    public String processMessage(String message) {
        return processMessage(message, null);
    }

    /** Process a user message with userId for user-dimension memory isolation. */
    public String processMessage(String message, String userId) {
        currentPhase = "thinking";
        try {
            // Write to MemoryStore before processing (user message)
            var memoryStore = tenantCtx.getCentralMemoryStore();
            if (memoryStore != null) {
                memoryStore.appendSessionMessage(
                    tenantCtx.getTenantId(), sessionId, "user", message);
            }

            // Retrieve user-scoped long-term memories
            if (memoryStore != null) {
                var longTermResults = memoryStore.searchMemories(
                    tenantCtx.getTenantId(), userId, message, 5);
                if (!longTermResults.isEmpty()) {
                    StringBuilder ctx = new StringBuilder();
                    ctx.append("\n\n[Relevant long-term memory]\n");
                    for (var entry : longTermResults) {
                        ctx.append("- ").append(entry.getContent()).append("\n");
                    }
                    message = message + ctx.toString();
                }
            }

            // Build AgentContext (single interface between agent and loop)
            var ctx = new AgentContext(delegate.getDelegate(), config);

            // AgentLoop.preLoop + run + postLoop, all through AgentContext
            boolean shouldReviewMemory = AgentLoop.preLoop(ctx, message);
            String loopResponse = AgentLoop.run(ctx, emitter);
            String finalResponse = AgentLoop.postLoop(ctx, loopResponse, shouldReviewMemory);

            // Write assistant response to MemoryStore
            if (memoryStore != null && finalResponse != null && !finalResponse.isBlank()) {
                memoryStore.appendSessionMessage(
                    tenantCtx.getTenantId(), sessionId, "assistant", finalResponse);
            }

            currentPhase = "idle";
            // Clear checkpoint on successful completion
            if (checkpointStore != null) {
                checkpointStore.delete(sessionId);
            }
            return finalResponse;
        } catch (Exception e) {
            // Save checkpoint on failure (for potential recovery)
            if (checkpointStore != null) {
                checkpointStore.save(sessionId, state);
            }
            currentPhase = "idle";
            throw e;
        }
    }

    /** Stream a user message (delegates to TenantAIAgent for streaming). */
    public void processMessageStream(String message, Consumer<String> onChunk) {
        processMessageStream(message, null, onChunk);
    }

    /** Stream a user message with userId for user-dimension memory isolation. */
    public void processMessageStream(String message, String userId, Consumer<String> onChunk) {
        currentPhase = "thinking";
        emitter.subscribe(e -> {
            if (e.type().equals(AgentEvent.LLM_DELTA)) {
                onChunk.accept((String) e.data().get("content"));
            }
        });
        try {
            delegate.processMessageStream(message, userId, onChunk);
            currentPhase = "idle";
        } catch (Exception e) {
            emitter.emit(AgentEvent.ERROR, Map.of("message", e.getMessage()));
            currentPhase = "idle";
            throw e;
        }
    }

    /** Set system prompt override. */
    public void setSystemPrompt(String prompt) {
        delegate.setSystemPrompt(prompt);
    }

    /** Set model params override. */
    public void setModelParams(Map<String, Object> params) {
        delegate.setModelParams(params);
    }

    /** Get debug info. */
    public Map<String, Object> getDebugInfo() {
        return delegate.getSessionDebugInfo();
    }

    /** End the session. */
    public void endSession(boolean completed) {
        delegate.endSession(completed);
        currentPhase = "idle";
        if (checkpointStore != null && completed) {
            checkpointStore.delete(sessionId);
        }
    }

    /** Save checkpoint (called on approval pause or shutdown). */
    public void saveCheckpoint() {
        if (checkpointStore != null) {
            checkpointStore.save(sessionId, state);
        }
    }

    /** Get the checkpoint store (if any). */
    public CheckpointStore checkpointStore() { return checkpointStore; }

    /** Stop the harness. */
    public void stop() {
        state.setLifecycle(LoopState.Lifecycle.STOPPED);
        currentPhase = "idle";
    }

    /** Get the event emitter (for SSE subscription). */
    public EventEmitter emitter() { return emitter; }

    /** Get the loop state. */
    public LoopState state() { return state; }

    /** Get session id. */
    public String sessionId() { return sessionId; }

    /** Last activity timestamp. */
    public long lastActivityMs() {
        return System.currentTimeMillis();
    }

    /** Current snapshot. */
    public HarnessSnapshot snapshot() {
        return HarnessSnapshot.from(sessionId, delegate.getSessionId(),
            tenantCtx.getTenantId(), state, startedAtMs);
    }
}
