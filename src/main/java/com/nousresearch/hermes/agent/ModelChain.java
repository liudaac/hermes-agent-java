package com.nousresearch.hermes.agent;

import com.nousresearch.hermes.config.HermesConfig;
import com.nousresearch.hermes.config.ModelRoute;
import com.nousresearch.hermes.model.ChatCompletionResponse;
import com.nousresearch.hermes.model.ModelClient;
import com.nousresearch.hermes.model.ModelMessage;
import com.nousresearch.hermes.model.ToolDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * F1: Multi-model orchestration - chain multiple models in a single task.
 *
 * <p>Allows an agent to use different models for different phases of a task:</p>
 * <pre>
 *   Step 1 (planner):   Claude-3.5-Sonnet  -> decompose task into steps
 *   Step 2 (executor):  GPT-4o              -> execute each step with tools
 *   Step 3 (reviewer):  DeepSeek-Reasoner    -> review and critique the result
 * </pre>
 *
 * <p>Models are resolved by alias from TenantConfig.model_routes,
 * so the agent code just says "use 'smart' for planning, 'fast' for execution".</p>
 *
 * <p>Each step gets the accumulated context from previous steps.</p>
 */
public class ModelChain {

    private static final Logger logger = LoggerFactory.getLogger(ModelChain.class);

    private final List<ChainStep> steps;
    private final List<Map<String, Object>> accumulatedContext;

    private ModelChain(List<ChainStep> steps) {
        this.steps = List.copyOf(steps);
        this.accumulatedContext = new ArrayList<>();
    }

    /**
     * Execute the chain: run each step in sequence, passing context forward.
     *
     * @param tenantConfig the tenant config for model resolution
     * @param globalConfig  global config for fallback
     * @param initialInput  the user's original message
     * @param tools         available tools for execution steps
     * @return the final step's output
     */
    public String execute(com.nousresearch.hermes.tenant.core.TenantConfig tenantConfig,
                          HermesConfig globalConfig,
                          String initialInput,
                          List<ToolDefinition> tools) {
        String currentInput = initialInput;
        accumulatedContext.add(Map.of("input", initialInput));

        for (int i = 0; i < steps.size(); i++) {
            ChainStep step = steps.get(i);
            logger.info("Chain step {}/{}: {} (alias={}, role={})",
                i + 1, steps.size(), step.name(), step.modelAlias(), step.role());

            // Resolve model config for this step
            HermesConfig.ModelConfig modelConfig = tenantConfig.resolveModelConfig(
                step.modelAlias(), globalConfig != null ? globalConfig.getModelRoutes() : null);

            // Create a temporary ModelClient for this step
            ModelClient stepClient = new ModelClient(modelConfig);

            // Build messages: system prompt for this role + accumulated context
            List<ModelMessage> messages = new ArrayList<>();
            messages.add(ModelMessage.system(step.systemPrompt()));
            messages.add(ModelMessage.user(currentInput));

            // Execute
            ChatCompletionResponse response = stepClient.chatCompletion(
                messages, tools, false, Map.of(), null);

            String output;
            if (response.getMessage() != null && response.getMessage().getContent() != null) {
                output = response.getMessage().getContent();
            } else if (response.getError() != null) {
                output = "[Error: " + response.getError() + "]";
            } else {
                output = "[No response]";
            }

            // Record context
            Map<String, Object> stepResult = new LinkedHashMap<>();
            stepResult.put("step", i + 1);
            stepResult.put("name", step.name());
            stepResult.put("role", step.role());
            stepResult.put("model", modelConfig.getName());
            stepResult.put("provider", modelConfig.getProvider());
            stepResult.put("input", currentInput);
            stepResult.put("output", output);
            accumulatedContext.add(stepResult);

            // Feed output as next step's input (unless this is the last step)
            if (i < steps.size() - 1) {
                currentInput = step.transformOutput() != null
                    ? step.transformOutput().apply(output, accumulatedContext)
                    : output;
            } else {
                currentInput = output;
            }
        }

        return currentInput;
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
         * @param systemPrompt system prompt for this step
         */
        public Builder step(String name, String modelAlias, String role, String systemPrompt) {
            steps.add(new ChainStep(name, modelAlias, role, systemPrompt, null));
            return this;
        }

        /**
         * Add a step with output transformation.
         */
        public Builder step(String name, String modelAlias, String role,
                            String systemPrompt, OutputTransform transform) {
            steps.add(new ChainStep(name, modelAlias, role, systemPrompt, transform));
            return this;
        }

        /**
         * Convenience: planning step (uses "smart" alias by default).
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
}
