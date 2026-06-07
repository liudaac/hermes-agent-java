package com.nousresearch.hermes.org.workflow;

import java.util.*;

/**
 * A single step in a workflow pipeline.
 *
 * <p>Steps can be:
 * <ul>
 *   <li><b>Tool calls</b> — delegate to a named agent tool</li>
 *   <li><b>Sub-agent invocations</b> — spawn a sub-agent for a subtask</li>
 *   <li><b>Human checkpoints</b> — pause and wait for human decision</li>
 *   <li><b>Conditional branches</b> — branch based on previous step results</li>
 * </ul>
 */
public class WorkflowStep {

    public enum Type { TOOL_CALL, SUB_AGENT, HUMAN_CHECKPOINT, CONDITIONAL, PARALLEL, SCRIPT }

    private final String name;
    private final Type type;
    private final String description;

    // For TOOL_CALL / SUB_AGENT / SCRIPT
    private final String target;         // tool name, agent ID, or script path
    private final Map<String, Object> parameters;

    // Dependencies: step names that must complete before this one
    private final Set<String> dependsOn;

    // For HUMAN_CHECKPOINT
    private final String decisionPrompt;
    private final List<String> decisionOptions;

    // For CONDITIONAL
    private final String conditionExpression;

    // Compensation
    private final boolean hasCompensation;
    private final String compensationTarget;
    private final Map<String, Object> compensationParameters;

    // Retry policy
    private final int maxRetries;
    private final long retryDelayMs;
    private final boolean retryOnError;

    // Timeout
    private final long timeoutMs;

    // Priority (higher = more important, for parallel execution order)
    private final int priority;

    private WorkflowStep(Builder builder) {
        this.name = Objects.requireNonNull(builder.name, "step name");
        this.type = Objects.requireNonNull(builder.type, "step type");
        this.description = builder.description != null ? builder.description : "";
        this.target = builder.target;
        this.parameters = Collections.unmodifiableMap(new LinkedHashMap<>(builder.parameters));
        this.dependsOn = Collections.unmodifiableSet(new LinkedHashSet<>(builder.dependsOn));
        this.decisionPrompt = builder.decisionPrompt;
        this.decisionOptions = Collections.unmodifiableList(new ArrayList<>(builder.decisionOptions));
        this.conditionExpression = builder.conditionExpression;
        this.hasCompensation = builder.hasCompensation;
        this.compensationTarget = builder.compensationTarget;
        this.compensationParameters = Collections.unmodifiableMap(new LinkedHashMap<>(builder.compensationParameters));
        this.maxRetries = builder.maxRetries;
        this.retryDelayMs = builder.retryDelayMs;
        this.retryOnError = builder.retryOnError;
        this.timeoutMs = builder.timeoutMs;
        this.priority = builder.priority;
    }

    // ---- convenience predicates ----

    public boolean isHumanCheckpoint() { return type == Type.HUMAN_CHECKPOINT; }
    public boolean isParallel() { return type == Type.PARALLEL; }
    public boolean isConditional() { return type == Type.CONDITIONAL; }
    public boolean hasCompensation() { return hasCompensation; }

    // ---- getters ----

    public String getName() { return name; }
    public Type getType() { return type; }
    public String getDescription() { return description; }
    public String getTarget() { return target; }
    public Map<String, Object> getParameters() { return parameters; }
    public Set<String> getDependsOn() { return dependsOn; }
    public String getDecisionPrompt() { return decisionPrompt; }
    public List<String> getDecisionOptions() { return decisionOptions; }
    public String getConditionExpression() { return conditionExpression; }
    public String getCompensationTarget() { return compensationTarget; }
    public Map<String, Object> getCompensationParameters() { return compensationParameters; }
    public int getMaxRetries() { return maxRetries; }
    public long getRetryDelayMs() { return retryDelayMs; }
    public boolean isRetryOnError() { return retryOnError; }
    public long getTimeoutMs() { return timeoutMs; }
    public int getPriority() { return priority; }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("type", type.name());
        m.put("description", description);
        m.put("target", target);
        m.put("depends_on", dependsOn);
        m.put("human_checkpoint", isHumanCheckpoint());
        m.put("has_compensation", hasCompensation);
        m.put("max_retries", maxRetries);
        m.put("timeout_ms", timeoutMs);
        return m;
    }

    // ---- builder ----

    public static class Builder {
        private final String name;
        private final Type type;
        private String description;
        private String target;
        private final Map<String, Object> parameters = new LinkedHashMap<>();
        private final Set<String> dependsOn = new LinkedHashSet<>();
        private String decisionPrompt;
        private final List<String> decisionOptions = new ArrayList<>();
        private String conditionExpression;
        private boolean hasCompensation;
        private String compensationTarget;
        private final Map<String, Object> compensationParameters = new LinkedHashMap<>();
        private int maxRetries = 0;
        private long retryDelayMs = 1000;
        private boolean retryOnError = false;
        private long timeoutMs = 300_000; // 5 min default
        private int priority = 0;

        public Builder(String name, Type type) {
            this.name = name;
            this.type = type;
        }

        public Builder description(String d) { this.description = d; return this; }
        public Builder target(String t) { this.target = t; return this; }
        public Builder param(String key, Object value) { parameters.put(key, value); return this; }
        public Builder dependsOn(String... stepNames) { Collections.addAll(dependsOn, stepNames); return this; }

        public Builder humanCheckpoint(String prompt, String... options) {
            this.decisionPrompt = prompt;
            Collections.addAll(decisionOptions, options);
            return this;
        }

        public Builder conditionExpression(String expr) {
            this.conditionExpression = expr;
            return this;
        }

        public Builder compensate(String target, Map<String, Object> params) {
            this.hasCompensation = true;
            this.compensationTarget = target;
            this.compensationParameters.putAll(params);
            return this;
        }

        public Builder retry(int maxRetries, long delayMs, boolean onError) {
            this.maxRetries = maxRetries; this.retryDelayMs = delayMs; this.retryOnError = onError;
            return this;
        }

        public Builder timeout(long ms) { this.timeoutMs = ms; return this; }
        public Builder priority(int p) { this.priority = p; return this; }

        public WorkflowStep build() { return new WorkflowStep(this); }
    }

    // ---- factory methods for common patterns ----

    public static WorkflowStep toolCall(String name, String toolName, Map<String, Object> params) {
        return new Builder(name, Type.TOOL_CALL).target(toolName).param("input", params).build();
    }

    public static WorkflowStep subAgent(String name, String agentId, String task) {
        return new Builder(name, Type.SUB_AGENT).target(agentId).param("task", task).build();
    }

    public static WorkflowStep humanApproval(String name, String prompt) {
        return new Builder(name, Type.HUMAN_CHECKPOINT)
            .humanCheckpoint(prompt, "Approve", "Reject")
            .build();
    }

    public static WorkflowStep conditional(String name, String expression) {
        return new Builder(name, Type.CONDITIONAL).conditionExpression(expression).build();
    }

    public static WorkflowStep parallel(String name, WorkflowStep... subSteps) {
        var step = new Builder(name, Type.PARALLEL).build();
        // Sub-steps are stored as parameters
        for (int i = 0; i < subSteps.length; i++) {
            step.parameters.put("sub_step_" + i, subSteps[i]);
        }
        return step;
    }
}