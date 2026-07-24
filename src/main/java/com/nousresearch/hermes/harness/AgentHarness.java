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

    private volatile LoopState state;
    private volatile EventEmitter emitter;
    private volatile String currentPhase = "idle";

    public AgentHarness(TenantContext tenantCtx, String sessionId, HermesConfig config) {
        this.tenantCtx = tenantCtx;
        this.sessionId = sessionId;
        this.startedAtMs = System.currentTimeMillis();
        this.state = new LoopState(config != null ? config.getMaxTurns() : 25);

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
    }

    /** Process a user message (delegates to TenantAIAgent). */
    public String processMessage(String message) {
        currentPhase = "thinking";
        emitter.emit(AgentEvent.LOOP_START, Map.of("budget", state.budget().getRemaining()));
        try {
            String response = delegate.processMessage(message);
            emitter.emit(AgentEvent.LOOP_END, Map.of("iterations", state.iterationsUsed()));
            currentPhase = "idle";
            return response;
        } catch (Exception e) {
            emitter.emit(AgentEvent.ERROR, Map.of("message", e.getMessage()));
            currentPhase = "idle";
            throw e;
        }
    }

    /** Stream a user message (delegates to TenantAIAgent). */
    public void processMessageStream(String message, Consumer<String> onChunk) {
        currentPhase = "thinking";
        emitter.emit(AgentEvent.LOOP_START, Map.of("budget", state.budget().getRemaining()));
        emitter.subscribe(e -> {
            if (e.type().equals(AgentEvent.LLM_DELTA)) {
                onChunk.accept((String) e.data().get("content"));
            }
        });
        try {
            delegate.processMessageStream(message, onChunk);
            emitter.emit(AgentEvent.LOOP_END, Map.of());
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
    }

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
