package com.nousresearch.hermes.scenario;

import com.nousresearch.hermes.collaboration.ScenarioOrchestrator;
import com.nousresearch.hermes.tenant.core.TenantContext;
import com.nousresearch.hermes.tenant.core.TenantManager;
import com.nousresearch.hermes.workspace.WorkspaceRecord;
import com.nousresearch.hermes.workspace.WorkspaceService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Adapter from Business Portal Scenario records to ScenarioOrchestrator inputs.
 *
 * <p>Scenario remains a business framing object. Planning, decomposition,
 * teammate selection, delegation and execution stay owned by the Hermes
 * collaboration foundation.</p>
 */
public class ScenarioIntentAdapter {
    private final WorkspaceService workspaceService;
    private final TenantManager tenantManager;

    public ScenarioIntentAdapter(WorkspaceService workspaceService, TenantManager tenantManager) {
        this.workspaceService = Objects.requireNonNull(workspaceService, "workspaceService");
        this.tenantManager = Objects.requireNonNull(tenantManager, "tenantManager");
    }

    /** Build the foundation request without planning or executing anything. */
    public ScenarioIntentRequest toIntentRequest(ScenarioRecord scenario, String userInput) {
        Objects.requireNonNull(scenario, "scenario");
        WorkspaceRecord workspace = workspaceService.requireWorkspace(scenario.getWorkspaceId());
        String intent = buildIntent(scenario, userInput);
        return new ScenarioIntentRequest(
            workspace.getWorkspaceId(),
            scenario.getScenarioId(),
            intent,
            blankToNull(scenario.getEntryTeamId()),
            allowDelegation(scenario),
            contextSignals(scenario),
            requestMetadata(scenario, workspace)
        );
    }

    /** Plan through ScenarioOrchestrator. This does not execute work. */
    public ScenarioOrchestrator.IntentPlan plan(ScenarioRecord scenario, String userInput) {
        ScenarioIntentRequest request = toIntentRequest(scenario, userInput);
        TenantContext tenant = requireTenant(request.workspaceId());
        return tenant.getScenarioOrchestrator().plan(
            request.intent(),
            request.preferredTeamId(),
            request.allowDelegation(),
            request.contextSignals()
        );
    }

    /** Execute through ScenarioOrchestrator. This returns the foundation IntentRun. */
    public ScenarioOrchestrator.IntentRun execute(ScenarioRecord scenario, String userInput) {
        ScenarioIntentRequest request = toIntentRequest(scenario, userInput);
        TenantContext tenant = requireTenant(request.workspaceId());
        return tenant.getScenarioOrchestrator().execute(
            request.intent(),
            request.preferredTeamId(),
            request.allowDelegation(),
            request.contextSignals()
        );
    }

    private TenantContext requireTenant(String workspaceId) {
        WorkspaceRecord workspace = workspaceService.requireWorkspace(workspaceId);
        TenantContext tenant = tenantManager.getTenant(workspace.getTenantId());
        if (tenant == null) {
            throw new IllegalStateException("Workspace tenant is not available: " + workspace.getTenantId());
        }
        return tenant;
    }

    private String buildIntent(ScenarioRecord scenario, String userInput) {
        StringBuilder sb = new StringBuilder();
        sb.append("Scenario: ").append(nonBlank(scenario.getName(), scenario.getScenarioId()));
        if (scenario.getDescription() != null && !scenario.getDescription().isBlank()) {
            sb.append("\nDescription: ").append(scenario.getDescription());
        }
        if (userInput != null && !userInput.isBlank()) {
            sb.append("\nUser request: ").append(userInput.trim());
        }
        if (scenario.getSuccessCriteria() != null && !scenario.getSuccessCriteria().isEmpty()) {
            sb.append("\nSuccess criteria:");
            for (String criterion : scenario.getSuccessCriteria()) {
                if (criterion != null && !criterion.isBlank()) {
                    sb.append("\n- ").append(criterion.trim());
                }
            }
        }
        if (scenario.getApprovalRules() != null && !scenario.getApprovalRules().isEmpty()) {
            sb.append("\nApproval rules:");
            for (String rule : scenario.getApprovalRules()) {
                if (rule != null && !rule.isBlank()) {
                    sb.append("\n- ").append(rule.trim());
                }
            }
        }
        return sb.toString();
    }

    private boolean allowDelegation(ScenarioRecord scenario) {
        Object raw = scenario.getMetadata() != null ? scenario.getMetadata().get("allowDelegation") : null;
        if (raw == null && scenario.getMetadata() != null) raw = scenario.getMetadata().get("allow_delegation");
        if (raw instanceof Boolean b) return b;
        if (raw != null) return Boolean.parseBoolean(String.valueOf(raw));
        return false;
    }

    private List<String> contextSignals(ScenarioRecord scenario) {
        List<String> signals = new ArrayList<>();
        addAllSignals(signals, scenario.getSuccessCriteria());
        if (scenario.getApprovalRules() != null && !scenario.getApprovalRules().isEmpty()) {
            signals.add("approval_required");
        }
        if (scenario.getMetadata() != null) {
            Object raw = scenario.getMetadata().get("contextSignals");
            if (raw == null) raw = scenario.getMetadata().get("context_signals");
            if (raw instanceof List<?> list) {
                for (Object item : list) {
                    if (item != null && !String.valueOf(item).isBlank()) signals.add(String.valueOf(item));
                }
            } else if (raw instanceof String s && !s.isBlank()) {
                for (String part : s.split("[,\\s]+")) {
                    if (!part.isBlank()) signals.add(part);
                }
            }
        }
        return signals.stream().filter(s -> s != null && !s.isBlank()).distinct().toList();
    }

    private void addAllSignals(List<String> signals, List<String> values) {
        if (values == null) return;
        for (String value : values) {
            if (value == null) continue;
            String lower = value.toLowerCase();
            if (lower.contains("production") || lower.contains("高风险") || lower.contains("人工审批") || lower.contains("refund")) {
                signals.add("high_stakes");
            }
            if (lower.contains("multi") || lower.contains("多步") || lower.contains("复杂")) {
                signals.add("multi_step");
            }
        }
    }

    private Map<String, Object> requestMetadata(ScenarioRecord scenario, WorkspaceRecord workspace) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "business-scenario");
        metadata.put("workspaceTenantId", workspace.getTenantId());
        metadata.put("scenarioStatus", scenario.getStatus());
        if (scenario.getMetadata() != null && !scenario.getMetadata().isEmpty()) {
            metadata.put("scenarioMetadata", Map.copyOf(scenario.getMetadata()));
        }
        return metadata;
    }

    private static String blankToNull(String value) {
        return value != null && !value.isBlank() ? value : null;
    }

    private static String nonBlank(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }
}
