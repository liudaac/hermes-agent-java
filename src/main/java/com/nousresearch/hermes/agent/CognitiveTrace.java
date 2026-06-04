package com.nousresearch.hermes.agent;

import java.time.Instant;
import java.util.Map;

/**
 * A single cognitive step in the agent's decision process.
 *
 * <p>Models the OODA loop (Observe → Orient → Decide → Act → Evaluate)
 * as a structured trace. These traces are kept out of the conversation
 * history to avoid token pollution, but are available for:</p>
 * <ul>
 *   <li>Reflection / self-critique (richer context than raw messages)</li>
 *   <li>Human explainability ("why did the agent do X?")</li>
 *   <li>Training data export (fine-tuning on decision chains)</li>
 * </ul>
 */
public record CognitiveTrace(
    Instant timestamp,
    int turn,
    Phase phase,
    String goal,
    String hypothesis,
    String action,
    String observation,
    String evaluation,
    String toolUsed,
    long durationMs,
    Map<String, Object> metadata
) {

    public enum Phase {
        /** User message arrived — what the agent perceives. */
        OBSERVE,
        /** Agent forms intent / plan before calling LLM. */
        ORIENT,
        /** LLM produces tool calls or response — the decision. */
        DECIDE,
        /** Tool executed or response delivered — the action. */
        ACT,
        /** Tool result or user reaction received — feedback. */
        EVALUATE
    }

    public static Builder builder(int turn, Phase phase) {
        return new Builder(turn, phase);
    }

    public static final class Builder {
        private final Instant timestamp = Instant.now();
        private final int turn;
        private final Phase phase;
        private String goal;
        private String hypothesis;
        private String action;
        private String observation;
        private String evaluation;
        private String toolUsed;
        private long durationMs;
        private Map<String, Object> metadata = Map.of();

        Builder(int turn, Phase phase) {
            this.turn = turn;
            this.phase = phase;
        }

        public Builder goal(String g) { this.goal = g; return this; }
        public Builder hypothesis(String h) { this.hypothesis = h; return this; }
        public Builder action(String a) { this.action = a; return this; }
        public Builder observation(String o) { this.observation = o; return this; }
        public Builder evaluation(String e) { this.evaluation = e; return this; }
        public Builder toolUsed(String t) { this.toolUsed = t; return this; }
        public Builder durationMs(long d) { this.durationMs = d; return this; }
        public Builder metadata(Map<String, Object> m) { this.metadata = m; return this; }

        public CognitiveTrace build() {
            return new CognitiveTrace(timestamp, turn, phase, goal, hypothesis,
                action, observation, evaluation, toolUsed, durationMs, metadata);
        }
    }
}
