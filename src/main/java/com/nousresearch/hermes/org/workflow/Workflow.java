package com.nousresearch.hermes.org.workflow;

import java.time.Instant;
import java.util.*;

/**
 * A persistent, long-running workflow definition.
 *
 * <p>Unlike the in-memory {@code TaskOrchestrator}, WorkflowEngine
 * serializes state at each checkpoint so workflows survive restarts,
 * support human-in-the-loop checkpoints, and implement Saga-style
 * compensation on failure.</p>
 */
public class Workflow {

    public enum Status {
        PENDING,       // Not yet started
        RUNNING,       // Actively executing
        WAITING,       // At a human checkpoint
        PAUSED,         // Manually paused
        COMPLETED,      // All steps successful
        FAILED,         // One or more steps failed
        COMPENSATING,   // Running compensation handlers
        COMPENSATED,    // All compensations applied
        CANCELLED       // Manually cancelled
    }

    private final String id;
    private final String name;
    private final String description;
    private final String owner;           // Agent or human who created it
    private final String tenantId;

    private volatile Status status = Status.PENDING;
    private final List<WorkflowStep> steps;
    private final Map<String, StepResult> results = new LinkedHashMap<>();

    // Saga compensation: if a step fails and has a compensation handler,
    // all completed steps that have compensations run them in reverse order.
    private final boolean sagaMode;

    private volatile int currentStepIndex;
    private final Instant createdAt;
    private volatile Instant updatedAt;
    private volatile Instant completedAt;
    private String errorMessage;

    // Metadata
    private final Map<String, Object> meta = new LinkedHashMap<>();
    private final Map<String, Object> context = new LinkedHashMap<>();  // shared across steps

    public Workflow(String id, String name, String description, String owner, String tenantId,
                    List<WorkflowStep> steps, boolean sagaMode) {
        this.id = Objects.requireNonNull(id);
        this.name = Objects.requireNonNull(name);
        this.description = description != null ? description : "";
        this.owner = Objects.requireNonNull(owner);
        this.tenantId = Objects.requireNonNull(tenantId);
        this.steps = List.copyOf(steps);
        this.sagaMode = sagaMode;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    // ---- state management ----

    public void markRunning() { status = Status.RUNNING; touch(); }
    public void markWaiting() { status = Status.WAITING; touch(); }
    public void markPaused() { status = Status.PAUSED; touch(); }
    public void markCompleted() { status = Status.COMPLETED; completedAt = Instant.now(); touch(); }
    public void markFailed(String error) { status = Status.FAILED; errorMessage = error; completedAt = Instant.now(); touch(); }
    public void markCompensating() { status = Status.COMPENSATING; touch(); }
    public void markCompensated() { status = Status.COMPENSATED; completedAt = Instant.now(); touch(); }
    public void markCancelled() { status = Status.CANCELLED; completedAt = Instant.now(); touch(); }

    public void recordStepResult(String stepName, StepResult result) {
        results.put(stepName, result);
        touch();
    }

    public void setCurrentStep(int index) { this.currentStepIndex = index; touch(); }
    public void putContext(String key, Object value) { context.put(key, value); }
    @SuppressWarnings("unchecked")
    /** 获取Context。 */
    public <T> T getContext(String key) { return (T) context.get(key); }

    // ---- progress ----

    /** 获取CurrentStep。 */
    public WorkflowStep getCurrentStep() {
        if (currentStepIndex < 0 || currentStepIndex >= steps.size()) return null;
        return steps.get(currentStepIndex);
    }

    /** 获取Progress。 */
    public double getProgress() {
        if (steps.isEmpty()) return 1.0;
        return (double) results.size() / steps.size();
    }

    public boolean isTerminal() {
        return status == Status.COMPLETED || status == Status.FAILED
            || status == Status.COMPENSATED || status == Status.CANCELLED;
    }

    public boolean needsHumanDecision() {
        return status == Status.WAITING && getCurrentStep() != null
            && getCurrentStep().isHumanCheckpoint();
    }

    // ---- compensation plan ----

    /** Build the list of steps to compensate in reverse order. */
    public List<WorkflowStep> buildCompensationPlan() {
        if (!sagaMode) return List.of();
        List<WorkflowStep> plan = new ArrayList<>();
        for (int i = currentStepIndex - 1; i >= 0; i--) {
            WorkflowStep step = steps.get(i);
            if (step.hasCompensation() && results.containsKey(step.getName())) {
                StepResult r = results.get(step.getName());
                if (r.success()) plan.add(step);
            }
        }
        return plan;
    }

    // ---- serialization ----

    /** 转Map。 */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("description", description);
        m.put("owner", owner);
        m.put("tenant_id", tenantId);
        m.put("status", status.name());
        m.put("saga_mode", sagaMode);
        m.put("total_steps", steps.size());
        m.put("current_step", currentStepIndex);
        m.put("completed_steps", results.size());
        m.put("progress", String.format("%.1f%%", getProgress() * 100));
        m.put("error", errorMessage);
        m.put("created_at", createdAt.toString());
        m.put("updated_at", updatedAt.toString());
        m.put("completed_at", completedAt != null ? completedAt.toString() : null);
        m.put("meta", meta);
        return m;
    }

    // ---- getters ----

    /** 获取Id。 */
    public String getId() { return id; }
    /** 获取Name。 */
    public String getName() { return name; }
    /** 获取Description。 */
    public String getDescription() { return description; }
    /** 获取Owner。 */
    public String getOwner() { return owner; }
    /** 获取TenantId。 */
    public String getTenantId() { return tenantId; }
    /** 获取Status。 */
    public Status getStatus() { return status; }
    /** 获取Steps。 */
    public List<WorkflowStep> getSteps() { return steps; }
    /** 获取Results。 */
    public Map<String, StepResult> getResults() { return Collections.unmodifiableMap(results); }
    public boolean isSagaMode() { return sagaMode; }
    /** 获取CurrentStepIndex。 */
    public int getCurrentStepIndex() { return currentStepIndex; }
    /** 获取CreatedAt。 */
    public Instant getCreatedAt() { return createdAt; }
    /** 获取UpdatedAt。 */
    public Instant getUpdatedAt() { return updatedAt; }
    /** 获取CompletedAt。 */
    public Instant getCompletedAt() { return completedAt; }
    /** 获取ErrorMessage。 */
    public String getErrorMessage() { return errorMessage; }
    /** 获取Meta。 */
    public Map<String, Object> getMeta() { return Collections.unmodifiableMap(meta); }
    /** 获取Context。 */
    public Map<String, Object> getContext() { return Collections.unmodifiableMap(context); }

    public Workflow meta(String key, Object value) { meta.put(key, value); return this; }

    private void touch() { this.updatedAt = Instant.now(); }

    /** Result of executing a single workflow step. */
    public record StepResult(String stepName, boolean success, String output, String error, Instant timestamp) {}
}
