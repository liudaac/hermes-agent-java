package com.nousresearch.hermes.harness;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nousresearch.hermes.agent.IterationBudget;
import com.nousresearch.hermes.model.ModelMessage;
import com.nousresearch.hermes.model.ToolCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Mutable state for a single agent loop execution.
 *
 * <p>Separated from {@link AgentContext} (which is mostly immutable) because
 * this object changes on every iteration: history grows, budget depletes,
 * checkpoint snapshots are created and restored.</p>
 *
 * <p>This object is designed to be {@link #serialize serializable} so that:
 * <ul>
 *   <li>Checkpoint/resume works across JVM restarts</li>
 *   <li>Future distributed mode can pass state between instances</li>
 *   <li>The debug panel can snapshot and display current state</li>
 * </ul></p>
 */
public class LoopState {
    private static final Logger logger = LoggerFactory.getLogger(LoopState.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    public enum Lifecycle {
        IDLE,                   // Not started or finished
        RUNNING,                // Actively executing the loop
        PAUSED_APPROVAL,        // Waiting for tool approval
        PAUSED_GOVERNANCE,      // Paused by governance policy
        STOPPED,                // Stopped by user or system
        FAILED                  // Error occurred
    }

    // ===== Core state =====

    private final List<ModelMessage> history;
    private final IterationBudget budget;
    private volatile Lifecycle lifecycle = Lifecycle.IDLE;
    private volatile int userTurnCount = 0;

    // ===== Memory/skill nudge counters =====

    private volatile int turnsSinceMemory = 0;
    private volatile int itersSinceSkill = 0;

    // ===== Background review =====

    private volatile boolean autoSkillsLoaded = false;

    // ===== Approval checkpoint =====

    private volatile LoopCheckpoint checkpoint;

    // ===== Last known model trace (for observability) =====

    private volatile com.nousresearch.hermes.org.observe.AgentTrace currentTrace;
    private volatile double lastTaskScore = 0.0;

    // ===== Interrupt flag =====

    private final java.util.concurrent.atomic.AtomicBoolean interrupted =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    // ===== Constructors =====

    public LoopState(int maxIterations) {
        this.history = new ArrayList<>();
        this.budget = new IterationBudget(maxIterations);
    }

    // ===== History management =====

    public List<ModelMessage> history() { return history; }

    public void addToHistory(ModelMessage message) {
        history.add(message);
    }

    public int historySize() { return history.size(); }

    public void trimHistory(int newSize) {
        while (history.size() > newSize) {
            history.remove(history.size() - 1);
        }
    }

    // ===== Budget =====

    public IterationBudget budget() { return budget; }
    public int iterationsRemaining() { return budget.getRemaining(); }
    public int iterationsUsed() { return budget.getUsed(); }

    // ===== Lifecycle =====

    public Lifecycle lifecycle() { return lifecycle; }
    public void setLifecycle(Lifecycle state) { this.lifecycle = state; }
    public boolean isRunning() { return lifecycle == Lifecycle.RUNNING; }
    public boolean isPaused() {
        return lifecycle == Lifecycle.PAUSED_APPROVAL || lifecycle == Lifecycle.PAUSED_GOVERNANCE;
    }

    // ===== Turn counting =====

    public int userTurnCount() { return userTurnCount; }
    public void incrementTurn() { userTurnCount++; }
    public void setUserTurnCount(int count) { this.userTurnCount = count; }

    // ===== Nudge counters =====

    public int turnsSinceMemory() { return turnsSinceMemory; }
    public void incrementTurnsSinceMemory() { turnsSinceMemory++; }
    public void resetTurnsSinceMemory() { turnsSinceMemory = 0; }

    public int itersSinceSkill() { return itersSinceSkill; }
    public void incrementItersSinceSkill() { itersSinceSkill++; }
    public void resetItersSinceSkill() { itersSinceSkill = 0; }

    // ===== Auto skills =====

    public boolean autoSkillsLoaded() { return autoSkillsLoaded; }
    public void setAutoSkillsLoaded(boolean loaded) { this.autoSkillsLoaded = loaded; }

    // ===== Interrupt =====

    public boolean isInterrupted() { return interrupted.get(); }
    public void interrupt() { interrupted.set(true); }

    // ===== Checkpoint =====

    public LoopCheckpoint checkpoint() { return checkpoint; }
    public void setCheckpoint(LoopCheckpoint cp) { this.checkpoint = cp; }
    public boolean hasCheckpoint() { return checkpoint != null; }
    public void clearCheckpoint() { checkpoint = null; }

    // ===== Trace =====

    public com.nousresearch.hermes.org.observe.AgentTrace currentTrace() { return currentTrace; }
    public void setCurrentTrace(com.nousresearch.hermes.org.observe.AgentTrace trace) {
        this.currentTrace = trace;
    }

    public double lastTaskScore() { return lastTaskScore; }
    public void setLastTaskScore(double score) { this.lastTaskScore = score; }

    // ===== Snapshot for checkpoint =====

    /**
     * Create a checkpoint snapshot of the current loop state.
     * Used when a tool call requires approval.
     */
    public LoopCheckpoint snapshot(ModelMessage assistantMessage,
                                    List<ToolCall> toolCalls,
                                    int pendingIndex,
                                    List<LoopCheckpoint.ToolCallResult> completedResults,
                                    int remainingIterations,
                                    int turnCount) {
        var cp = new LoopCheckpoint(
            assistantMessage,
            new ArrayList<>(toolCalls),
            pendingIndex,
            new ArrayList<>(completedResults),
            history.size(),
            remainingIterations,
            turnCount
        );
        this.checkpoint = cp;
        return cp;
    }

    // ===== Serialization =====

    /**
     * Serialize the loop state to JSON for persistence/debugging.
     * Note: ModelMessage serialization depends on its JSON structure.
     */
    public String serialize() {
        try {
            var node = JSON.createObjectNode();
            node.put("lifecycle", lifecycle.name());
            node.put("userTurnCount", userTurnCount);
            node.put("turnsSinceMemory", turnsSinceMemory);
            node.put("itersSinceSkill", itersSinceSkill);
            node.put("iterationsUsed", budget.getUsed());
            node.put("iterationsMax", budget.getRemaining() + budget.getUsed());
            node.put("lastTaskScore", lastTaskScore);
            node.put("interrupted", interrupted.get());
            node.put("autoSkillsLoaded", autoSkillsLoaded);

            // History
            var historyArray = node.putArray("history");
            for (var msg : history) {
                var msgNode = historyArray.addObject();
                msgNode.put("role", msg.getRole());
                msgNode.put("content", msg.getContent());
            }

            return JSON.writeValueAsString(node);
        } catch (Exception e) {
            logger.error("Failed to serialize LoopState", e);
            return "{}";
        }
    }

    /**
     * Deserialize a LoopState from JSON.
     * Note: This restores history and counters but not checkpoint (must be restored separately).
     */
    public static LoopState deserialize(String json, int maxIterations) {
        try {
            var node = JSON.readTree(json);
            var state = new LoopState(maxIterations);

            state.lifecycle = Lifecycle.valueOf(node.get("lifecycle").asText("IDLE"));
            state.userTurnCount = node.get("userTurnCount").asInt(0);
            state.turnsSinceMemory = node.get("turnsSinceMemory").asInt(0);
            state.itersSinceSkill = node.get("itersSinceSkill").asInt(0);
            state.lastTaskScore = node.get("lastTaskScore").asDouble(0.0);
            state.autoSkillsLoaded = node.get("autoSkillsLoaded").asBoolean(false);

            // Restore history
            if (node.has("history")) {
                for (var msgNode : node.get("history")) {
                    state.history.add(new ModelMessage(
                        msgNode.get("role").asText(),
                        msgNode.get("content").asText()
                    ));
                }
            }

            // Restore budget
            int used = node.get("iterationsUsed").asInt(0);
            for (int i = 0; i < used; i++) {
                state.budget.consume();
            }

            return state;
        } catch (Exception e) {
            logger.error("Failed to deserialize LoopState", e);
            return new LoopState(maxIterations);
        }
    }

    /** Reset the state for a new conversation (keeps the same object). */
    public void reset() {
        history.clear();
        budget.reset();
        lifecycle = Lifecycle.IDLE;
        userTurnCount = 0;
        turnsSinceMemory = 0;
        itersSinceSkill = 0;
        autoSkillsLoaded = false;
        checkpoint = null;
        currentTrace = null;
        lastTaskScore = 0.0;
        interrupted.set(false);
    }
}
