package com.nousresearch.hermes.org.handoff;

import java.time.Instant;
import java.util.*;

/**
 * Encapsulates the full context for a human-agent handoff.
 *
 * <p>When an agent reaches a decision boundary it cannot (or should not)
 * resolve autonomously, it packages everything a human reviewer needs
 * to understand the situation and make an informed decision.</p>
 */
public class HandoffContext {

    public enum Priority { LOW, NORMAL, HIGH, CRITICAL }

    /** Unique handoff ID. */
    private final String handoffId;

    /** The agent requesting human intervention. */
    private final String sourceAgentId;

    /** Priority level (affects SLA and escalation timing). */
    private final Priority priority;

    /** Short summary (1-2 sentences) of what's needed. */
    private final String summary;

    /** Detailed explanation of the situation. */
    private final String situation;

    /** What decision needs to be made, presented as options. */
    private final List<HandoffOption> options;

    /** Conversation history leading to this point (truncated). */
    private final List<MessageSnapshot> contextMessages;

    /** Agent's recommendation (if any). */
    private String recommendation;
    private double confidence;

    /** Tool calls already executed and their results. */
    private final List<ExecutedAction> actionsTaken;

    /** Files / artifacts the human should review. */
    private final List<String> artifacts;

    /** Who should review this (role, team, or specific person). */
    private final String targetReviewer;

    /** Maximum acceptable wait before escalation. */
    private final long maxWaitSeconds;

    /** Escalation chain if not resolved in time. */
    private final List<String> escalationChain;

    /** When this handoff was created. */
    private final Instant createdAt;

    /** Current status. */
    private volatile Status status = Status.PENDING;
    private volatile String resolvedBy;
    private volatile String selectedOption;
    private volatile String resolutionNote;
    private volatile Instant resolvedAt;

    public enum Status { PENDING, ACKNOWLEDGED, RESOLVED, ESCALATED, TIMED_OUT, CANCELLED }

    private HandoffContext(Builder builder) {
        this.handoffId = builder.handoffId != null ? builder.handoffId : UUID.randomUUID().toString();
        this.sourceAgentId = Objects.requireNonNull(builder.sourceAgentId, "sourceAgentId");
        this.priority = builder.priority != null ? builder.priority : Priority.NORMAL;
        this.summary = Objects.requireNonNull(builder.summary, "summary");
        this.situation = Objects.requireNonNull(builder.situation, "situation");
        this.options = Collections.unmodifiableList(new ArrayList<>(builder.options));
        this.contextMessages = Collections.unmodifiableList(new ArrayList<>(builder.contextMessages));
        this.recommendation = builder.recommendation;
        this.confidence = builder.confidence;
        this.actionsTaken = Collections.unmodifiableList(new ArrayList<>(builder.actionsTaken));
        this.artifacts = Collections.unmodifiableList(new ArrayList<>(builder.artifacts));
        this.targetReviewer = builder.targetReviewer;
        this.maxWaitSeconds = builder.maxWaitSeconds;
        this.escalationChain = Collections.unmodifiableList(new ArrayList<>(builder.escalationChain));
        this.createdAt = Instant.now();
    }

    /** Acknowledge receipt (human has seen it). */
    public synchronized void acknowledge(String reviewer) {
        if (status != Status.PENDING) return;
        this.status = Status.ACKNOWLEDGED;
    }

    /** Resolve the handoff with a decision. */
    public synchronized HandoffResolution resolve(String reviewer, String option, String note) {
        this.status = Status.RESOLVED;
        this.resolvedBy = reviewer;
        this.selectedOption = option;
        this.resolutionNote = note;
        this.resolvedAt = Instant.now();
        return new HandoffResolution(handoffId, option, note, reviewer, resolvedAt);
    }

    /** Mark as timed out. */
    public synchronized void markTimeout() {
        if (status == Status.PENDING || status == Status.ACKNOWLEDGED) {
            this.status = Status.TIMED_OUT;
        }
    }

    /** Mark as escalated. */
    public synchronized void markEscalated() {
        this.status = Status.ESCALATED;
    }

    /** Cancel the handoff (agent resolved it itself). */
    public synchronized void cancel() {
        if (status == Status.PENDING || status == Status.ACKNOWLEDGED) {
            this.status = Status.CANCELLED;
        }
    }

    /** Check if the handoff has exceeded its max wait time. */
    public boolean isOverdue() {
        return Instant.now().isAfter(createdAt.plusSeconds(maxWaitSeconds));
    }

    /** Get SLA-remaining seconds. Negative means overdue. */
    public long getRemainingSeconds() {
        return maxWaitSeconds - (Instant.now().getEpochSecond() - createdAt.getEpochSecond());
    }

    // ---- getters ----
    public String getHandoffId() { return handoffId; }
    public String getSourceAgentId() { return sourceAgentId; }
    public Priority getPriority() { return priority; }
    public String getSummary() { return summary; }
    public String getSituation() { return situation; }
    public List<HandoffOption> getOptions() { return options; }
    public List<MessageSnapshot> getContextMessages() { return contextMessages; }
    public String getRecommendation() { return recommendation; }
    public double getConfidence() { return confidence; }
    public List<ExecutedAction> getActionsTaken() { return actionsTaken; }
    public List<String> getArtifacts() { return artifacts; }
    public String getTargetReviewer() { return targetReviewer; }
    public long getMaxWaitSeconds() { return maxWaitSeconds; }
    public List<String> getEscalationChain() { return escalationChain; }
    public Instant getCreatedAt() { return createdAt; }
    public Status getStatus() { return status; }
    public String getResolvedBy() { return resolvedBy; }
    public String getSelectedOption() { return selectedOption; }
    public String getResolutionNote() { return resolutionNote; }
    public Instant getResolvedAt() { return resolvedAt; }

    /** A decision option presented to the human. */
    public record HandoffOption(String id, String label, String description, String impact) {}

    /** Snapshot of a conversation message for context. */
    public record MessageSnapshot(String role, String content, Instant timestamp) {}

    /** An action the agent already took. */
    public record ExecutedAction(String tool, String input, String result, Instant timestamp) {}

    /** The resolution of a handoff. */
    public record HandoffResolution(String handoffId, String option, String note, String reviewer, Instant at) {}

    public static class Builder {
        private String handoffId;
        private String sourceAgentId;
        private Priority priority = Priority.NORMAL;
        private String summary;
        private String situation;
        private final List<HandoffOption> options = new ArrayList<>();
        private final List<MessageSnapshot> contextMessages = new ArrayList<>();
        private String recommendation;
        private double confidence;
        private final List<ExecutedAction> actionsTaken = new ArrayList<>();
        private final List<String> artifacts = new ArrayList<>();
        private String targetReviewer;
        private long maxWaitSeconds = 3600; // 1 hour default
        private final List<String> escalationChain = new ArrayList<>();

        public Builder(String sourceAgentId, String summary, String situation) {
            this.sourceAgentId = sourceAgentId;
            this.summary = summary;
            this.situation = situation;
        }

        public Builder handoffId(String id) { this.handoffId = id; return this; }
        public Builder priority(Priority p) { this.priority = p; return this; }
        public Builder addOption(String id, String label, String desc) { return addOption(id, label, desc, ""); }
        public Builder addOption(String id, String label, String desc, String impact) {
            options.add(new HandoffOption(id, label, desc, impact)); return this;
        }
        public Builder addContextMessage(String role, String content, Instant ts) {
            contextMessages.add(new MessageSnapshot(role, content, ts)); return this;
        }
        public Builder recommendation(String rec, double confidence) {
            this.recommendation = rec; this.confidence = confidence; return this;
        }
        public Builder addActionTaken(String tool, String input, String result) {
            actionsTaken.add(new ExecutedAction(tool, input, result, Instant.now())); return this;
        }
        public Builder addArtifact(String path) { artifacts.add(path); return this; }
        public Builder targetReviewer(String reviewer) { this.targetReviewer = reviewer; return this; }
        public Builder maxWaitSeconds(long seconds) { this.maxWaitSeconds = seconds; return this; }
        public Builder escalateTo(String... reviewers) {
            Collections.addAll(escalationChain, reviewers); return this;
        }
        public HandoffContext build() { return new HandoffContext(this); }
    }
}