package com.nousresearch.hermes.agent;

import com.nousresearch.hermes.config.HermesConfig;
import com.nousresearch.hermes.model.ChatCompletionResponse;
import com.nousresearch.hermes.model.ModelClient;
import com.nousresearch.hermes.model.ModelMessage;
import com.nousresearch.hermes.model.ToolDefinition;
import com.nousresearch.hermes.observability.ExecutionTrace;
import com.nousresearch.hermes.observability.TraceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Multi-model orchestration - chain multiple models in a single task.
 *
 * <p>Three-phase pipeline with structured plan and retry loop:</p>
 * <pre>
 *   Phase 1 (planner):   smart model  -> decompose task into ExecutionPlan
 *   Phase 2 (executor):  fast model    -> execute each PlanStep, using specified tools
 *   Phase 3 (reviewer):  smart model    -> review results, retry failed steps if needed
 * </p>
 *
 * <p>Models are resolved by alias from TenantConfig.model_routes.
 * Planner outputs {@link ExecutionPlan} JSON; if steps is empty, executor
 * passes through the original input as a single-turn conversation.</p>
 */
public class ModelChain {

    private static final Logger logger = LoggerFactory.getLogger(ModelChain.class);

    private static final int DEFAULT_MAX_RETRIES = 2;

    private final List<ChainStep> steps;
    private final List<Map<String, Object>> accumulatedContext;

    private TraceStore traceStore;
    private String tenantId;
    private String sessionId;
    private String agentId;

    // Interrupt control: checked between phases and between steps
    private volatile boolean interrupted = false;

    private ModelChain(List<ChainStep> steps) {
        this.steps = List.copyOf(steps);
        this.accumulatedContext = new ArrayList<>();
    }

    /**
     * Interrupt the chain execution. The current phase/step will complete,
     * but no further phases or steps will start.
     */
    public void interrupt() {
        this.interrupted = true;
        logger.info("Chain interrupted (tenant={}, agent={})", tenantId, agentId);
    }

    public boolean isInterrupted() {
        return interrupted;
    }

    /**
     * Check interruption flag, throw if interrupted.
     * Called between phases and between execution steps.
     */
    private void checkInterrupted() {
        if (interrupted) {
            throw new ChainInterruptedException(
                "Chain execution interrupted by user request");
        }
    }

    /**
     * Set trace store for observability. When set, each chain execution
     * creates an ExecutionTrace with spans for planner/executor/reviewer phases.
     */
    public ModelChain withTraceStore(TraceStore traceStore) {
        this.traceStore = traceStore;
        return this;
    }

    /**
     * Set tenant/session/agent context for trace attribution.
     */
    public ModelChain withContext(String tenantId, String sessionId, String agentId) {
        this.tenantId = tenantId;
        this.sessionId = sessionId;
        this.agentId = agentId;
        return this;
    }

    /**
     * Execute the chain with structured plan + retry loop.
     *
     * <p>Returns {@link ChainResult} containing the output string, traceId,
     * and the full ExecutionPlan for API exposure.</p>
     *
     * @param tenantConfig the tenant config for model resolution
     * @param globalConfig  global config for fallback
     * @param initialInput  the user's original message
     * @param tools         available tools for execution steps
     * @return ChainResult with output, traceId, and plan
     */
    public ChainResult execute(com.nousresearch.hermes.tenant.core.TenantConfig tenantConfig,
                          HermesConfig globalConfig,
                          String initialInput,
                          List<ToolDefinition> tools) {
        accumulatedContext.add(Map.of("input", initialInput));

        // Create execution trace for observability
        ExecutionTrace trace = new ExecutionTrace(
            tenantId != null ? tenantId : "unknown",
            sessionId != null ? sessionId : "chain",
            agentId != null ? agentId : "chain");

        // Find steps by role
        ChainStep plannerStep = findStepByRole("planner");
        ChainStep executorStep = findStepByRole("executor");
        ChainStep reviewerStep = findStepByRole("reviewer");

        // If no role-based steps, fall back to legacy sequential execution
        if (plannerStep == null && executorStep == null && reviewerStep == null) {
            String result = executeLegacy(tenantConfig, globalConfig, initialInput, tools);
            trace.complete();
            if (traceStore != null) traceStore.store(trace);
            return new ChainResult(result, trace.getTraceId(), null, trace);
        }

        // ---- Phase 1: Planner ----
        ExecutionTrace.TraceSpan plannerSpan = trace.addSpan("planner", "model_call");
        String plannerPrompt = plannerStep != null
            ? plannerStep.systemPrompt()
            : PlannerPrompt.buildSystemPrompt(tools);

        ExecutionPlan plan = runPlanner(tenantConfig, globalConfig, plannerStep, plannerPrompt, initialInput, tools);
        plannerSpan.addAttribute("goal", plan.goal());
        plannerSpan.addAttribute("stepCount", plan.isPassthrough() ? 0 : plan.steps().size());
        plannerSpan.addAttribute("passthrough", plan.isPassthrough());
        plannerSpan.complete();
        logPhase("planner", plan.goal(), plan.isPassthrough() ? "[passthrough]" : plan.steps().size() + " steps");

        // Check interruption between phases
        checkInterrupted();

        // ---- Phase 2: Executor ----
        ExecutionTrace.TraceSpan executorSpan = trace.addSpan("executor", "agent_message");
        Map<String, String> stepOutputs = new LinkedHashMap<>();
        String executorResult;

        if (plan.isPassthrough()) {
            executorResult = runExecutorSingle(tenantConfig, globalConfig, executorStep, initialInput, tools);
            stepOutputs.put("passthrough", executorResult);
            executorSpan.addAttribute("mode", "passthrough");
        } else {
            executorResult = runExecutorSteps(tenantConfig, globalConfig, executorStep, plan, tools, stepOutputs, trace);
            executorSpan.addAttribute("mode", "multi-step");
            executorSpan.addAttribute("stepCount", plan.steps().size());
        }
        executorSpan.addAttribute("outputLength", executorResult.length());
        executorSpan.complete();
        logPhase("executor", "completed", executorResult.substring(0, Math.min(executorResult.length(), 200)));

        // Check interruption before reviewer
        checkInterrupted();

        // ---- Phase 3: Reviewer (with retry loop) ----
        String finalResult = executorResult;
        int totalRetries = 0;

        if (reviewerStep != null && !plan.isPassthrough()) {
            ExecutionTrace.TraceSpan reviewerSpan = trace.addSpan("reviewer", "model_call");
            int maxRetries = DEFAULT_MAX_RETRIES;
            int attempt = 0;

            while (attempt <= maxRetries) {
                // Check interruption before each review attempt
                checkInterrupted();

                ExecutionPlan.ReviewResult review = runReviewer(
                    tenantConfig, globalConfig, reviewerStep, plan, stepOutputs);

                logPhase("reviewer", "attempt " + attempt,
                    review.approved() ? "APPROVED" : "RETRY: " + review.retryStepIds());

                if (review.approved()) {
                    reviewerSpan.addAttribute("approved", true);
                    finalResult = buildFinalOutput(plan, stepOutputs, review);
                    break;
                }

                if (!review.needsRetry() || attempt >= maxRetries) {
                    reviewerSpan.addAttribute("approved", false);
                    reviewerSpan.addAttribute("retriesExhausted", true);
                    finalResult = buildFinalOutput(plan, stepOutputs, review);
                    break;
                }

                // Retry failed steps
                attempt++;
                totalRetries++;
                List<String> retryIds = review.retryStepIds();
                logger.info("Retrying {} steps (attempt {}/{})", retryIds.size(), attempt, maxRetries);

                for (String stepId : retryIds) {
                    ExecutionPlan.PlanStep step = plan.findStep(stepId);
                    if (step == null) continue;

                    String feedback = review.stepReviews().stream()
                        .filter(s -> stepId.equals(s.stepId()))
                        .map(ExecutionPlan.StepReview::feedback)
                        .findFirst()
                        .orElse("");

                    ExecutionTrace.TraceSpan retrySpan = trace.addSpan("retry:" + stepId, "model_call");
                    String retryOutput = runExecutorStep(tenantConfig, globalConfig, executorStep,
                        plan, step, stepOutputs, feedback, tools);
                    stepOutputs.put(stepId, retryOutput);
                    retrySpan.addAttribute("stepId", stepId);
                    retrySpan.addAttribute("feedback", feedback);
                    retrySpan.complete();
                }
            }
            reviewerSpan.addAttribute("totalRetries", totalRetries);
            reviewerSpan.complete();
        }

        trace.complete();
        if (traceStore != null) traceStore.store(trace);

        accumulatedContext.add(Map.of("finalResult", finalResult));
        return new ChainResult(finalResult, trace.getTraceId(), plan, trace);
    }

    /**
     * Result of a chain execution.
     *
     * @param output   the final output string
     * @param traceId  trace ID for querying execution progress via /api/traces/{traceId}
     * @param plan     the ExecutionPlan from planner (null if legacy mode)
     * @param trace    the full ExecutionTrace object with all spans
     */
    public record ChainResult(
            String output,
            String traceId,
            ExecutionPlan plan,
            ExecutionTrace trace
    ) {
        /**
         * API-friendly representation for HTTP response.
         */
        public Map<String, Object> toApi() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("output", output);
            m.put("traceId", traceId);
            if (plan != null) {
                m.put("goal", plan.goal());
                m.put("stepCount", plan.isPassthrough() ? 0 : plan.steps().size());
                m.put("passthrough", plan.isPassthrough());
                if (!plan.isPassthrough()) {
                    m.put("steps", plan.steps().stream().map(s -> Map.of(
                        "id", s.id(),
                        "action", s.action(),
                        "tool", s.tool() != null ? s.tool() : "",
                        "dependsOn", s.dependsOn() != null ? s.dependsOn() : List.of()
                    )).toList());
                }
                m.put("successCriteria", plan.successCriteria());
            }
            if (trace != null) {
                m.put("status", trace.getStatus());
                m.put("durationMs", trace.getDurationMs());
                m.put("spanCount", trace.getSpans().size());
            }
            return m;
        }
    }

    // ============ Phase implementations ============

    private ExecutionPlan runPlanner(com.nousresearch.hermes.tenant.core.TenantConfig tenantConfig,
                                     HermesConfig globalConfig,
                                     ChainStep plannerStep,
                                     String plannerSystemPrompt,
                                     String userInput,
                                     List<ToolDefinition> tools) {
        // Use custom prompt if provided, otherwise use the schema-aware default
        String systemPrompt = plannerStep != null && plannerStep.systemPrompt() != null
            && !plannerStep.systemPrompt().isBlank()
                ? plannerStep.systemPrompt()
                : plannerSystemPrompt;

        // If the custom prompt doesn't include tool list, append it
        if (!systemPrompt.contains("可用工具") && !systemPrompt.contains("Available tools") && tools != null && !tools.isEmpty()) {
            systemPrompt = systemPrompt + "\n\n" + PlannerPrompt.buildSystemPrompt(tools);
        }

        List<ModelMessage> messages = new ArrayList<>();
        messages.add(ModelMessage.system(systemPrompt));
        messages.add(ModelMessage.user(userInput));

        ChatCompletionResponse response = callModel(tenantConfig, globalConfig,
            plannerStep != null ? plannerStep.modelAlias() : "smart", messages, null);

        String output = extractContent(response);
        ExecutionPlan plan = PlannerPrompt.parsePlan(output);

        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("phase", "planner");
        ctx.put("output", output);
        ctx.put("plan", plan);
        accumulatedContext.add(ctx);

        return plan;
    }

    private String runExecutorSingle(com.nousresearch.hermes.tenant.core.TenantConfig tenantConfig,
                                     HermesConfig globalConfig,
                                     ChainStep executorStep,
                                     String userInput,
                                     List<ToolDefinition> tools) {
        String systemPrompt = executorStep != null && executorStep.systemPrompt() != null
            ? executorStep.systemPrompt()
            : "You are a task executor. Complete the following task using available tools.";

        List<ModelMessage> messages = new ArrayList<>();
        messages.add(ModelMessage.system(systemPrompt));
        messages.add(ModelMessage.user(userInput));

        ChatCompletionResponse response = callModel(tenantConfig, globalConfig,
            executorStep != null ? executorStep.modelAlias() : "fast", messages, tools);

        String output = extractContent(response);

        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("phase", "executor");
        ctx.put("mode", "passthrough");
        ctx.put("output", output);
        accumulatedContext.add(ctx);

        return output;
    }

    private String runExecutorSteps(com.nousresearch.hermes.tenant.core.TenantConfig tenantConfig,
                                    HermesConfig globalConfig,
                                    ChainStep executorStep,
                                    ExecutionPlan plan,
                                    List<ToolDefinition> tools,
                                    Map<String, String> stepOutputs,
                                    ExecutionTrace trace) {
        String executorSystemPrompt = executorStep != null && executorStep.systemPrompt() != null
            ? executorStep.systemPrompt()
            : "You are a task executor. Execute each step precisely. Use the specified tool when provided.";

        String lastOutput = "";

        for (ExecutionPlan.PlanStep step : plan.steps()) {
            // Check interruption between steps
            checkInterrupted();

            // Check dependencies are done
            if (step.hasDependencies()) {
                for (String depId : step.dependsOn()) {
                    if (!stepOutputs.containsKey(depId)) {
                        logger.warn("Step {} depends on {} which hasn't been executed yet, skipping", step.id(), depId);
                        stepOutputs.put(step.id(), "[Skipped: dependency " + depId + " not completed]");
                        continue;
                    }
                }
            }

            ExecutionTrace.TraceSpan stepSpan = trace.addSpan("step:" + step.id(), "tool_call");
            stepSpan.addAttribute("action", step.action());
            stepSpan.addAttribute("tool", step.tool() != null ? step.tool() : "reasoning");

            String output = runExecutorStep(tenantConfig, globalConfig, executorStep,
                plan, step, stepOutputs, null, tools);
            stepOutputs.put(step.id(), output);
            stepSpan.addAttribute("outputLength", output.length());
            stepSpan.complete();
            lastOutput = output;
        }

        return lastOutput;
    }

    /**
     * Execute a single PlanStep. Builds context from previous step outputs + dependencies.
     */
    private String runExecutorStep(com.nousresearch.hermes.tenant.core.TenantConfig tenantConfig,
                                   HermesConfig globalConfig,
                                   ChainStep executorStep,
                                   ExecutionPlan plan,
                                   ExecutionPlan.PlanStep step,
                                   Map<String, String> stepOutputs,
                                   String reviewerFeedback,
                                   List<ToolDefinition> tools) {
        // Filter tools to only the one specified by planner (if any)
        List<ToolDefinition> stepTools = step.isToolStep()
            ? filterTools(tools, step.tool())
            : null;

        // Build context: plan goal + this step's action + dependency outputs
        StringBuilder userMsg = new StringBuilder();
        userMsg.append("Plan goal: ").append(plan.goal()).append("\n\n");
        userMsg.append("Your task (step ").append(step.id()).append("): ").append(step.action()).append("\n");

        if (step.expectedOutput() != null && !step.expectedOutput().isBlank()) {
            userMsg.append("Expected output: ").append(step.expectedOutput()).append("\n");
        }

        // Include dependency outputs
        if (step.hasDependencies()) {
            userMsg.append("\nPrevious step results:\n");
            for (String depId : step.dependsOn()) {
                String depOutput = stepOutputs.get(depId);
                if (depOutput != null) {
                    userMsg.append("--- ").append(depId).append(" ---\n");
                    userMsg.append(truncate(depOutput, 2000)).append("\n\n");
                }
            }
        }

        // Include reviewer feedback if this is a retry
        if (reviewerFeedback != null && !reviewerFeedback.isBlank()) {
            userMsg.append("\n⚠ Reviewer feedback (please address): ").append(reviewerFeedback).append("\n");
        }

        List<ModelMessage> messages = new ArrayList<>();
        messages.add(ModelMessage.system(executorStep != null ? executorStep.systemPrompt()
            : "You are a task executor. Execute each step precisely. Use the specified tool when provided."));
        messages.add(ModelMessage.user(userMsg.toString()));

        ChatCompletionResponse response = callModel(tenantConfig, globalConfig,
            executorStep != null ? executorStep.modelAlias() : "fast", messages, stepTools);

        String output = extractContent(response);

        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("phase", "executor");
        ctx.put("stepId", step.id());
        ctx.put("stepAction", step.action());
        ctx.put("tool", step.tool());
        ctx.put("isRetry", reviewerFeedback != null);
        ctx.put("output", output);
        accumulatedContext.add(ctx);

        return output;
    }

    private ExecutionPlan.ReviewResult runReviewer(com.nousresearch.hermes.tenant.core.TenantConfig tenantConfig,
                                                   HermesConfig globalConfig,
                                                   ChainStep reviewerStep,
                                                   ExecutionPlan plan,
                                                   Map<String, String> stepOutputs) {
        // Build reviewer prompt with plan + step outputs
        List<String> outputs = plan.steps().stream()
            .map(s -> stepOutputs.getOrDefault(s.id(), "[not executed]"))
            .toList();

        String systemPrompt = reviewerStep != null && reviewerStep.systemPrompt() != null
            && !reviewerStep.systemPrompt().isBlank()
                ? reviewerStep.systemPrompt()
                : PlannerPrompt.buildReviewerPrompt(plan, outputs);

        // If custom prompt, append the plan + outputs
        if (reviewerStep != null && reviewerStep.systemPrompt() != null
            && !reviewerStep.systemPrompt().isBlank()
            && !reviewerStep.systemPrompt().contains("approved")) {
            systemPrompt = systemPrompt + "\n\n" + PlannerPrompt.buildReviewerPrompt(plan, outputs);
        }

        List<ModelMessage> messages = new ArrayList<>();
        messages.add(ModelMessage.system(systemPrompt));
        messages.add(ModelMessage.user("Review the execution results above."));

        ChatCompletionResponse response = callModel(tenantConfig, globalConfig,
            reviewerStep != null ? reviewerStep.modelAlias() : "smart", messages, null);

        String output = extractContent(response);
        ExecutionPlan.ReviewResult review = PlannerPrompt.parseReview(output);

        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("phase", "reviewer");
        ctx.put("rawOutput", output);
        ctx.put("review", review);
        accumulatedContext.add(ctx);

        return review;
    }

    // ============ Legacy sequential execution (no plan/review) ============

    private String executeLegacy(com.nousresearch.hermes.tenant.core.TenantConfig tenantConfig,
                                 HermesConfig globalConfig,
                                 String initialInput,
                                 List<ToolDefinition> tools) {
        String currentInput = initialInput;

        for (int i = 0; i < steps.size(); i++) {
            ChainStep step = steps.get(i);
            logger.info("Legacy chain step {}/{}: {} (alias={}, role={})",
                i + 1, steps.size(), step.name(), step.modelAlias(), step.role());

            List<ModelMessage> messages = new ArrayList<>();
            messages.add(ModelMessage.system(step.systemPrompt()));
            messages.add(ModelMessage.user(currentInput));

            ChatCompletionResponse response = callModel(tenantConfig, globalConfig,
                step.modelAlias(), messages, tools);

            String output = extractContent(response);

            Map<String, Object> stepResult = new LinkedHashMap<>();
            stepResult.put("step", i + 1);
            stepResult.put("name", step.name());
            stepResult.put("role", step.role());
            stepResult.put("input", currentInput);
            stepResult.put("output", output);
            accumulatedContext.add(stepResult);

            if (i < steps.size() - 1 && step.transformOutput() != null) {
                currentInput = step.transformOutput().apply(output, accumulatedContext);
            } else {
                currentInput = output;
            }
        }

        return currentInput;
    }

    // ============ Helpers ============

    private ChainStep findStepByRole(String role) {
        return steps.stream()
            .filter(s -> role.equalsIgnoreCase(s.role()))
            .findFirst()
            .orElse(null);
    }

    private ChatCompletionResponse callModel(com.nousresearch.hermes.tenant.core.TenantConfig tenantConfig,
                                             HermesConfig globalConfig,
                                             String alias,
                                             List<ModelMessage> messages,
                                             List<ToolDefinition> tools) {
        HermesConfig.ModelConfig modelConfig = tenantConfig.resolveModelConfig(
            alias, globalConfig != null ? globalConfig.getModelRoutes() : null);
        ModelClient client = new ModelClient(modelConfig);
        return client.chatCompletion(messages, tools, false, Map.of(), null);
    }

    private String extractContent(ChatCompletionResponse response) {
        if (response.getMessage() != null && response.getMessage().getContent() != null) {
            return response.getMessage().getContent();
        }
        if (response.getError() != null) {
            return "[Error: " + response.getError() + "]";
        }
        return "[No response]";
    }

    private List<ToolDefinition> filterTools(List<ToolDefinition> tools, String toolName) {
        if (tools == null || toolName == null) return null;
        return tools.stream()
            .filter(t -> toolName.equals(t.getName()))
            .toList();
    }

    private String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...[truncated]";
    }

    private String buildFinalOutput(ExecutionPlan plan, Map<String, String> stepOutputs,
                                     ExecutionPlan.ReviewResult review) {
        StringBuilder sb = new StringBuilder();
        sb.append("Goal: ").append(plan.goal()).append("\n\n");
        sb.append("Steps:\n");
        for (ExecutionPlan.PlanStep step : plan.steps()) {
            sb.append("[").append(step.id()).append("] ").append(step.action());
            String output = stepOutputs.get(step.id());
            if (output != null) {
                sb.append("\n  -> ").append(truncate(output, 500));
            }
            sb.append("\n");
        }
        sb.append("\nReview: ").append(review.summary());
        if (!review.approved()) {
            sb.append(" (retries exhausted)");
        }
        return sb.toString();
    }

    private void logPhase(String phase, String detail1, String detail2) {
        logger.info("[{}] {} | {}", phase, detail1, detail2);
    }

    /**
     * Get the full execution trace (for debugging/observability).
     */
    public List<Map<String, Object>> getTrace() {
        return List.copyOf(accumulatedContext);
    }

    // ============ Builder ============

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final List<ChainStep> steps = new ArrayList<>();

        /**
         * Add a step to the chain.
         *
         * @param name         step name (e.g. "planning")
         * @param modelAlias   model alias from model_routes (e.g. "smart")
         * @param role         role description (e.g. "planner")
         * @param systemPrompt system prompt for this step (null/blank = use default)
         */
        public Builder step(String name, String modelAlias, String role, String systemPrompt) {
            steps.add(new ChainStep(name, modelAlias, role, systemPrompt, null));
            return this;
        }

        /**
         * Add a step with output transformation (legacy mode only).
         */
        public Builder step(String name, String modelAlias, String role,
                            String systemPrompt, OutputTransform transform) {
            steps.add(new ChainStep(name, modelAlias, role, systemPrompt, transform));
            return this;
        }

        /**
         * Convenience: planning step (uses "smart" alias by default).
         * Pass null/blank systemPrompt to use the schema-aware default.
         */
        public Builder plan(String systemPrompt) {
            return step("planning", "smart", "planner", systemPrompt);
        }

        /**
         * Convenience: execution step (uses "fast" alias by default).
         */
        public Builder execute(String systemPrompt) {
            return step("execution", "fast", "executor", systemPrompt);
        }

        /**
         * Convenience: review step (uses "smart" alias by default).
         */
        public Builder review(String systemPrompt) {
            return step("review", "smart", "reviewer", systemPrompt);
        }

        /**
         * Convenience: create a standard plan->execute->review chain
         * with default prompts (all null = schema-aware defaults).
         */
        public ModelChain buildDefault() {
            steps.add(new ChainStep("planning", "smart", "planner", null, null));
            steps.add(new ChainStep("execution", "fast", "executor", null, null));
            steps.add(new ChainStep("review", "smart", "reviewer", null, null));
            return new ModelChain(steps);
        }

        public ModelChain build() {
            if (steps.isEmpty()) {
                throw new IllegalStateException("ModelChain must have at least one step");
            }
            return new ModelChain(steps);
        }
    }

    // ============ Types ============

    public record ChainStep(
            String name,
            String modelAlias,
            String role,
            String systemPrompt,
            OutputTransform transformOutput
    ) {}

    @FunctionalInterface
    public interface OutputTransform {
        String apply(String output, List<Map<String, Object>> context);
    }

    /**
     * Thrown when chain execution is interrupted via {@link #interrupt()}.
     */
    public static class ChainInterruptedException extends RuntimeException {
        public ChainInterruptedException(String message) {
            super(message);
        }
    }
}
