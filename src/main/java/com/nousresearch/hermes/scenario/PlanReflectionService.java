package com.nousresearch.hermes.scenario;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.nousresearch.hermes.model.ModelClient;
import com.nousresearch.hermes.model.ModelMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Plan-Execute-Reflect engine — adds explicit planning visibility and
 * per-step self-reflection to scenario execution.
 *
 * <p>After each subtask completes, the engine asks an LLM:</p>
 * <ol>
 *   <li>Does the result align with the original intent?</li>
 *   <li>Should the remaining plan be adjusted?</li>
 *   <li>What is the next step recommendation?</li>
 * </ol>
 *
 * <p>Reflection summaries are attached to {@link com.nousresearch.hermes.business.run.BusinessRunStep}
 * metadata for display in the story UI.</p>
 */
public class PlanReflectionService {

    private static final Logger logger = LoggerFactory.getLogger(PlanReflectionService.class);

    private final ModelClient modelClient;

    public PlanReflectionService(ModelClient modelClient) {
        this.modelClient = modelClient;
    }

    /**
     * Reflect on a completed step within the context of the overall plan.
     *
     * @param intent          the original user intent / task description
     * @param planSteps       all planned steps (titles)
     * @param completedSteps  steps already finished with their results
     * @param currentStep     the step just completed
     * @param currentResult   the result of the current step
     * @return reflection result with alignment, replan flag, and narrative
     */
    public ReflectionResult reflect(String intent, List<String> planSteps,
                                     List<CompletedStep> completedSteps,
                                     String currentStep, String currentResult) {
        if (modelClient == null) {
            return ReflectionResult.ok("Reflection skipped — no model client configured.");
        }

        try {
            List<ModelMessage> messages = buildReflectPrompt(intent, planSteps, completedSteps, currentStep, currentResult);
            var response = modelClient.chatCompletion(messages, null, false);
            if (response == null || !response.isSuccess()) {
                return ReflectionResult.ok("Reflection inconclusive — model returned empty.");
            }
            return parseReflection(response.getContent());
        } catch (Exception e) {
            logger.warn("Reflection failed for step '{}': {}", currentStep, e.getMessage());
            return ReflectionResult.ok("Reflection error — continuing with original plan.");
        }
    }

    /**
     * Generate a human-readable plan summary from assignments.
     */
    public String summarizePlan(String intent, List<String> stepTitles) {
        StringBuilder sb = new StringBuilder();
        sb.append("Plan for: ").append(intent).append("\n\n");
        for (int i = 0; i < stepTitles.size(); i++) {
            sb.append(i + 1).append(". ").append(stepTitles.get(i)).append("\n");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Prompt construction
    // ------------------------------------------------------------------

    private List<ModelMessage> buildReflectPrompt(String intent, List<String> planSteps,
                                                   List<CompletedStep> completedSteps,
                                                   String currentStep, String currentResult) {
        StringBuilder user = new StringBuilder();
        user.append("Original task: ").append(intent).append("\n\n");
        user.append("Planned steps:\n");
        for (int i = 0; i < planSteps.size(); i++) {
            user.append(i + 1).append(". ").append(planSteps.get(i)).append("\n");
        }
        if (!completedSteps.isEmpty()) {
            user.append("\nCompleted steps:\n");
            for (CompletedStep s : completedSteps) {
                user.append("- ").append(s.title).append(": ")
                    .append(truncate(s.result, 200)).append("\n");
            }
        }
        user.append("\nJust completed: ").append(currentStep).append("\n");
        user.append("Result: ").append(truncate(currentResult, 300)).append("\n\n");
        user.append("Respond in JSON:\n");
        user.append("  aligned: true/false (does the result advance the task?)\n");
        user.append("  confidence: 0.0-1.0\n");
        user.append("  replan: true/false (should remaining steps be adjusted?)\n");
        user.append("  narrative: one-sentence reflection for the user\n");
        user.append("  suggestion: what to do next (if replan is true)\n");

        return List.of(
            ModelMessage.system("You are a self-reflection assistant for an AI agent team. Evaluate whether a completed step advances the original task. Be concise."),
            ModelMessage.user(user.toString())
        );
    }

    private ReflectionResult parseReflection(String json) {
        String raw = json.trim();
        if (raw.startsWith("```")) {
            int s = raw.indexOf("{");
            int e = raw.lastIndexOf("}");
            if (s >= 0 && e > s) raw = raw.substring(s, e + 1);
        }
        JSONObject obj = JSON.parseObject(raw);
        boolean aligned = obj.getBoolean("aligned") != null ? obj.getBoolean("aligned") : true;
        double confidence = obj.getDouble("confidence") != null ? obj.getDouble("confidence") : 1.0;
        boolean replan = obj.getBoolean("replan") != null ? obj.getBoolean("replan") : false;
        String narrative = obj.getString("narrative");
        String suggestion = obj.getString("suggestion");
        return new ReflectionResult(aligned, confidence, replan, narrative, suggestion);
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "...";
    }

    // ------------------------------------------------------------------
    // DTOs
    // ------------------------------------------------------------------

    public static class CompletedStep {
        public final String title;
        public final String result;

        public CompletedStep(String title, String result) {
            this.title = title;
            this.result = result;
        }
    }

    public static class ReflectionResult {
        public final boolean aligned;
        public final double confidence;
        public final boolean replan;
        public final String narrative;
        public final String suggestion;

        public ReflectionResult(boolean aligned, double confidence, boolean replan,
                                String narrative, String suggestion) {
            this.aligned = aligned;
            this.confidence = confidence;
            this.replan = replan;
            this.narrative = narrative != null ? narrative : "Step completed.";
            this.suggestion = suggestion;
        }

        public static ReflectionResult ok(String narrative) {
            return new ReflectionResult(true, 1.0, false, narrative, null);
        }
    }
}
