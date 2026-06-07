package com.nousresearch.hermes.org.evolution;

import java.time.Instant;
import java.util.*;

/**
 * Failure case analysis — captures what went wrong and what the agent
 * learned from it. Used by the SelfEvolutionEngine to build a library
 * of lessons learned across the organization.
 */
public class FailureCase {

    public enum RootCause {
        AMBIGUOUS_PROMPT,     // Task wasn't clearly defined
        WRONG_TOOL,            // Agent chose the wrong tool
        INSUFFICIENT_CONTEXT,   // Missing critical information
        HALLUCINATION,         // Agent fabricated information
        TIMEOUT,              // Operation exceeded time limit
        PERMISSION_DENIED,     // Agent lacked required permission
        EXTERNAL_FAILURE,      // External service failed
        LOGIC_ERROR,           // Agent reasoning was flawed
        PARTIAL_COMPLETION,    // Task was only partially done
        UNKNOWN
    }

    public enum Severity { LOW, MEDIUM, HIGH, CRITICAL }

    private final String id;
    private final String agentId;
    private final String taskDescription;
    private final String expectedOutcome;
    private final String actualOutcome;
    private final RootCause rootCause;
    private final Severity severity;
    private final String diagnosis;        // Detailed analysis by the agent
    private final String lesson;           // What the agent learned
    private final List<String> correctiveActions;  // Changes made to prevent recurrence
    private final Map<String, String> contextHints; // What context would have helped
    private final boolean resolved;
    private final Instant occurredAt;
    private final Instant analyzedAt;

    private FailureCase(Builder builder) {
        this.id = builder.id != null ? builder.id : UUID.randomUUID().toString().substring(0, 8);
        this.agentId = Objects.requireNonNull(builder.agentId);
        this.taskDescription = Objects.requireNonNull(builder.taskDescription);
        this.expectedOutcome = builder.expectedOutcome != null ? builder.expectedOutcome : "";
        this.actualOutcome = Objects.requireNonNull(builder.actualOutcome);
        this.rootCause = builder.rootCause != null ? builder.rootCause : RootCause.UNKNOWN;
        this.severity = builder.severity != null ? builder.severity : Severity.MEDIUM;
        this.diagnosis = builder.diagnosis != null ? builder.diagnosis : "";
        this.lesson = builder.lesson != null ? builder.lesson : "";
        this.correctiveActions = List.copyOf(builder.correctiveActions);
        this.contextHints = Collections.unmodifiableMap(new LinkedHashMap<>(builder.contextHints));
        this.resolved = builder.resolved;
        this.occurredAt = builder.occurredAt != null ? builder.occurredAt : Instant.now();
        this.analyzedAt = Instant.now();
    }

    /** Generate a system prompt injection to help prevent recurrence. */
    public String toPromptInjection() {
        StringBuilder sb = new StringBuilder();
        sb.append("## Lesson Learned: ").append(lesson).append("\n");
        sb.append("Cause: ").append(rootCause).append(" | Severity: ").append(severity).append("\n");
        sb.append("Context: ").append(taskDescription).append("\n");
        if (!correctiveActions.isEmpty()) {
            sb.append("Preventive measures:\n");
            for (String action : correctiveActions) sb.append("- ").append(action).append("\n");
        }
        if (!contextHints.isEmpty()) {
            sb.append("Missing context to request next time:\n");
            for (var hint : contextHints.entrySet()) sb.append("- ").append(hint.getKey()).append(": ").append(hint.getValue()).append("\n");
        }
        return sb.toString();
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("agent_id", agentId);
        m.put("task", taskDescription);
        m.put("root_cause", rootCause.name());
        m.put("severity", severity.name());
        m.put("lesson", lesson);
        m.put("resolved", resolved);
        m.put("occurred", occurredAt.toString());
        return m;
    }

    // ---- getters ----
    public String getId() { return id; }
    public String getAgentId() { return agentId; }
    public String getTaskDescription() { return taskDescription; }
    public String getExpectedOutcome() { return expectedOutcome; }
    public String getActualOutcome() { return actualOutcome; }
    public RootCause getRootCause() { return rootCause; }
    public Severity getSeverity() { return severity; }
    public String getLesson() { return lesson; }
    public List<String> getCorrectiveActions() { return correctiveActions; }
    public Map<String, String> getContextHints() { return contextHints; }
    public boolean isResolved() { return resolved; }
    public Instant getOccurredAt() { return occurredAt; }

    public static class Builder {
        private String id;
        private String agentId;
        private String taskDescription;
        private String expectedOutcome;
        private String actualOutcome;
        private RootCause rootCause = RootCause.UNKNOWN;
        private Severity severity = Severity.MEDIUM;
        private String diagnosis;
        private String lesson;
        private final List<String> correctiveActions = new ArrayList<>();
        private final Map<String, String> contextHints = new LinkedHashMap<>();
        private boolean resolved;
        private Instant occurredAt;

        public Builder(String agentId, String taskDescription, String actualOutcome) {
            this.agentId = agentId; this.taskDescription = taskDescription; this.actualOutcome = actualOutcome;
        }
        public Builder id(String id) { this.id = id; return this; }
        public Builder expectedOutcome(String o) { this.expectedOutcome = o; return this; }
        public Builder rootCause(RootCause rc) { this.rootCause = rc; return this; }
        public Builder severity(Severity s) { this.severity = s; return this; }
        public Builder diagnosis(String d) { this.diagnosis = d; return this; }
        public Builder lesson(String l) { this.lesson = l; return this; }
        public Builder correctiveAction(String action) { correctiveActions.add(action); return this; }
        public Builder contextHint(String key, String value) { contextHints.put(key, value); return this; }
        public Builder resolved(boolean r) { this.resolved = r; return this; }
        public Builder occurredAt(Instant t) { this.occurredAt = t; return this; }
        public FailureCase build() { return new FailureCase(this); }
    }
}