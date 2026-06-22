package com.nousresearch.hermes.collaboration;

import com.nousresearch.hermes.collaboration.pattern.CollaborationPattern;
import com.nousresearch.hermes.model.ModelClient;
import com.nousresearch.hermes.model.ModelMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Intent-aware task decomposer.
 *
 * <p>Replaces the heuristic comma-split with structured decomposition:
 * <ul>
 *   <li><b>LLM-based</b>: uses the model client to intelligently break down complex intents</li>
 *   <li><b>Template-based</b>: matches against known business scenario templates</li>
 *   <li><b>Heuristic fallback</b>: original comma/and/then splitting</li>
 * </ul>
 */
public class IntentDecomposer {
    private static final Logger logger = LoggerFactory.getLogger(IntentDecomposer.class);

    private final ModelClient modelClient;
    private final Map<String, ScenarioTemplate> templates = new LinkedHashMap<>();

    public IntentDecomposer() {
        this(null);
    }

    public IntentDecomposer(ModelClient modelClient) {
        this.modelClient = modelClient;
        seedDefaultTemplates();
    }

    /**
     * Decompose an intent into structured subtask plans.
     *
     * @param intent          the user intent
     * @param pattern         preferred collaboration pattern (may influence decomposition)
     * @param availableRoles  agent roles available for matching
     * @return list of subtask plans with suggested assignees
     */
    public List<SubtaskPlan> decompose(String intent, CollaborationPattern pattern,
                                        Map<String, AgentRuntimeProfile> availableRoles) {
        // 1. Try template match first (fastest, most reliable for known patterns)
        List<SubtaskPlan> templateMatch = matchTemplate(intent, pattern, availableRoles);
        if (!templateMatch.isEmpty()) {
            logger.debug("Intent matched template: {} subtasks", templateMatch.size());
            return templateMatch;
        }

        // 2. Try LLM-based decomposition if client available
        if (modelClient != null) {
            try {
                List<SubtaskPlan> llmPlans = decomposeWithLLM(intent, pattern, availableRoles);
                if (!llmPlans.isEmpty()) {
                    return llmPlans;
                }
            } catch (Exception e) {
                logger.warn("LLM decomposition failed, falling back to heuristic: {}", e.getMessage());
            }
        }

        // 3. Heuristic fallback
        return decomposeHeuristic(intent, pattern, availableRoles);
    }

    /**
     * Register a reusable scenario template.
     */
    public void registerTemplate(String name, ScenarioTemplate template) {
        templates.put(name, template);
    }

    // ---- Template matching ----

    private List<SubtaskPlan> matchTemplate(String intent, CollaborationPattern pattern,
                                             Map<String, AgentRuntimeProfile> roles) {
        String normalized = intent.toLowerCase(Locale.ROOT);
        for (ScenarioTemplate template : templates.values()) {
            if (template.matches(normalized)) {
                return template.instantiate(intent, pattern, roles);
            }
        }
        return List.of();
    }

    // ---- LLM-based decomposition ----

    private List<SubtaskPlan> decomposeWithLLM(String intent, CollaborationPattern pattern,
                                                Map<String, AgentRuntimeProfile> roles) {
        String prompt = buildDecompositionPrompt(intent, pattern, roles);
        var messages = List.of(new com.nousresearch.hermes.model.ModelMessage("user", prompt));
        var response = modelClient.chatCompletion(messages, null, false, null);
        String content = response != null && response.getContent() != null ? response.getContent() : "";
        return parseDecompositionResponse(content, roles);
    }

    private String buildDecompositionPrompt(String intent, CollaborationPattern pattern,
                                             Map<String, AgentRuntimeProfile> roles) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a task decomposition engine. Break down the following business intent into discrete subtasks.\n\n");
        sb.append("Intent: \"").append(intent).append("\"\n");
        sb.append("Collaboration Pattern: ").append(pattern != null ? pattern.name() : "SEQUENTIAL").append("\n\n");
        sb.append("Available Agents:\n");
        for (var entry : roles.entrySet()) {
            sb.append("- ").append(entry.getKey()).append(": ")
              .append(entry.getValue().getRoleName()).append(" | Skills: ")
              .append(String.join(", ", entry.getValue().getSkills())).append("\n");
        }
        sb.append("\nRespond in this exact format (one subtask per line):\n");
        sb.append("SUBTASK: <subtask description> | AGENT_HINT: <best_agent_id_or_empty> | DEPENDS_ON: <comma_list_or_empty>\n");
        sb.append("Keep subtasks atomic and specific.");
        return sb.toString();
    }

    private List<SubtaskPlan> parseDecompositionResponse(String response,
                                                          Map<String, AgentRuntimeProfile> roles) {
        List<SubtaskPlan> plans = new ArrayList<>();
        for (String line : response.split("\\n")) {
            line = line.trim();
            if (!line.toUpperCase().startsWith("SUBTASK:")) continue;

            String[] parts = line.substring(8).split("\\|");
            if (parts.length < 1) continue;

            String subtask = parts[0].trim();
            String agentHint = parts.length > 1 ? extractValue(parts[1], "AGENT_HINT") : "";
            String depsRaw = parts.length > 2 ? extractValue(parts[2], "DEPENDS_ON") : "";

            Set<String> deps = depsRaw.isBlank()
                ? Set.of()
                : Arrays.stream(depsRaw.split(",")).map(String::trim).filter(s -> !s.isBlank()).collect(Collectors.toSet());

            plans.add(new SubtaskPlan(subtask, agentHint, deps));
        }
        return plans;
    }

    private String extractValue(String part, String key) {
        String normalized = part.trim();
        if (normalized.toUpperCase().startsWith(key + ":")) {
            return normalized.substring(key.length() + 1).trim();
        }
        return "";
    }

    // ---- Heuristic fallback ----

    private List<SubtaskPlan> decomposeHeuristic(String intent, CollaborationPattern pattern,
                                                  Map<String, AgentRuntimeProfile> roles) {
        List<String> parts = ScenarioOrchestrator.decompose(intent);
        List<SubtaskPlan> plans = new ArrayList<>();
        String lastSubtask = null;
        for (String part : parts) {
            Set<String> deps = (pattern == CollaborationPattern.PIPELINE || pattern == CollaborationPattern.SEQUENTIAL)
                && lastSubtask != null ? Set.of(lastSubtask) : Set.of();
            plans.add(new SubtaskPlan(part, "", deps));
            lastSubtask = part;
        }
        return plans;
    }

    // ---- Default templates ----

    private void seedDefaultTemplates() {
        // Order processing template
        registerTemplate("order_processing", new ScenarioTemplate(
            List.of("order", "下单", "订单", "purchase"),
            List.of(
                new SubtaskPlan("Validate order information", "validator", Set.of()),
                new SubtaskPlan("Check inventory availability", "inventory", Set.of("Validate order information")),
                new SubtaskPlan("Process payment", "payment", Set.of("Check inventory availability")),
                new SubtaskPlan("Create fulfillment request", "fulfillment", Set.of("Process payment")),
                new SubtaskPlan("Send confirmation notification", "notification", Set.of("Create fulfillment request"))
            )
        ));

        // Customer service template
        registerTemplate("customer_service", new ScenarioTemplate(
            List.of("customer", "客服", "退款", "return", "complaint", "投诉"),
            List.of(
                new SubtaskPlan("Classify customer inquiry", "classifier", Set.of()),
                new SubtaskPlan("Retrieve customer history", "history", Set.of("Classify customer inquiry")),
                new SubtaskPlan("Draft response or solution", "responder", Set.of("Retrieve customer history")),
                new SubtaskPlan("Review for policy compliance", "reviewer", Set.of("Draft response or solution"))
            )
        ));

        // Inventory alert template
        registerTemplate("inventory_alert", new ScenarioTemplate(
            List.of("inventory", "库存", "补货", "restock", "low stock"),
            List.of(
                new SubtaskPlan("Analyze inventory levels", "analyzer", Set.of()),
                new SubtaskPlan("Forecast demand", "forecaster", Set.of("Analyze inventory levels")),
                new SubtaskPlan("Generate purchase order", "purchaser", Set.of("Forecast demand")),
                new SubtaskPlan("Notify supplier", "notifier", Set.of("Generate purchase order"))
            )
        ));
    }

    // ---- Inner classes ----

    /** A reusable scenario template for intent matching. */
    public static class ScenarioTemplate {
        private final List<String> keywords;
        private final List<SubtaskPlan> defaultSteps;

        public ScenarioTemplate(List<String> keywords, List<SubtaskPlan> defaultSteps) {
            this.keywords = keywords;
            this.defaultSteps = defaultSteps;
        }

        boolean matches(String normalizedIntent) {
            return keywords.stream().anyMatch(normalizedIntent::contains);
        }

        List<SubtaskPlan> instantiate(String intent, CollaborationPattern pattern,
                                       Map<String, AgentRuntimeProfile> roles) {
            // Clone steps and optionally reassign agents based on availability
            List<SubtaskPlan> instantiated = new ArrayList<>();
            for (SubtaskPlan step : defaultSteps) {
                String agentId = resolveAgentHint(step.agentHint(), roles);
                instantiated.add(new SubtaskPlan(step.subtask(), agentId, step.dependsOn()));
            }
            return instantiated;
        }

        private String resolveAgentHint(String hint, Map<String, AgentRuntimeProfile> roles) {
            if (hint == null || hint.isBlank()) return "";
            if (roles.containsKey(hint)) return hint;
            // Fuzzy match by role name
            return roles.entrySet().stream()
                .filter(e -> e.getValue().getRoleName().toLowerCase().contains(hint.toLowerCase()))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse("");
        }
    }

    /** A planned subtask with decomposition metadata. */
    public record SubtaskPlan(
        String subtask,
        String agentHint,
        Set<String> dependsOn
    ) {}
}
