package com.nousresearch.hermes.business.foundation;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Read-only diagnostics projection for the Business Portal foundation boundary.
 *
 * <p>This class does not touch runtime state, create routes, add UI or execute
 * foundation operations. It describes the current adapter baseline so future
 * product integrations can verify they are entering through the facade.</p>
 */
public class BusinessPortalFoundationDiagnostics {

    public DiagnosticsReport inspect(BusinessPortalFoundationFacade facade) {
        Objects.requireNonNull(facade, "facade");
        List<AdapterStatus> adapters = List.of(
            adapter("promptAssetResolver", facade.promptAssetResolver(), "prompt:// resolution and PromptContext projection"),
            adapter("capabilityValidator", facade.capabilityValidator(), "TeamBlueprint capability grounding report"),
            adapter("teamBlueprintCompiler", facade.teamBlueprintCompiler(), "TeamBlueprintVersion -> Team / AgentRole topology"),
            adapter("scenarioIntentAdapter", facade.scenarioIntentAdapter(), "ScenarioRecord -> IntentOrchestrator input/plan/execute"),
            adapter("runProjectionAdapter", facade.runProjectionAdapter(), "IntentRun / AgentTrace -> BusinessRunRecord projection"),
            adapter("approvalAdapter", facade.approvalAdapter(), "ApprovalRequest / ApprovalResult -> BusinessApprovalRecord projection"),
            adapter("insightProjectionAdapter", facade.insightProjectionAdapter(), "AgentTrace / Eval / Evolution summary -> BusinessInsight projection"),
            adapter("evolutionProposalAdapter", facade.evolutionProposalAdapter(), "EvolutionProposal -> FailureCase / Approval / DelegatedTask boundaries")
        );
        return new DiagnosticsReport(
            Instant.now(),
            "BusinessPortalFoundationFacade",
            adapters,
            guardrails(),
            nonGoals(),
            true
        );
    }

    private AdapterStatus adapter(String name, Object instance, String role) {
        return new AdapterStatus(
            name,
            instance != null,
            instance != null ? instance.getClass().getName() : "",
            role
        );
    }

    private List<String> guardrails() {
        return List.of(
            "Future API/UI/generation code should enter foundation behavior through BusinessPortalFoundationFacade.",
            "Team blueprints must be grounded by FoundationCapabilityValidator before compile/apply.",
            "Scenario execution must go through ScenarioIntentAdapter / IntentOrchestrator.",
            "BusinessRunRecord must preserve technicalTraceRef when projecting foundation runs/traces.",
            "BusinessApprovalRecord must mirror ApprovalRequest / ApprovalResult instead of becoming an approval engine.",
            "Evolution proposals must go through SelfEvolutionEngine / ApprovalSystem / DelegatedTaskStore boundaries.",
            "Business insights should distinguish file-backed business summaries from foundation-backed trace/eval/evolution projections."
        );
    }

    private List<String> nonGoals() {
        return List.of(
            "No generation API",
            "No Business Portal UI expansion",
            "No new business domain objects",
            "No runtime team execution from blueprint compile",
            "No autonomous evolution proposal apply",
            "No direct channel notification delivery",
            "No second approval engine, trace store, workflow engine or knowledge store"
        );
    }

    public record DiagnosticsReport(
        Instant generatedAt,
        String boundary,
        List<AdapterStatus> adapters,
        List<String> guardrails,
        List<String> nonGoals,
        boolean facadeReady
    ) {
        public DiagnosticsReport {
            adapters = adapters != null ? List.copyOf(adapters) : List.of();
            guardrails = guardrails != null ? List.copyOf(guardrails) : List.of();
            nonGoals = nonGoals != null ? List.copyOf(nonGoals) : List.of();
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("generatedAt", generatedAt.toString());
            map.put("boundary", boundary);
            map.put("facadeReady", facadeReady);
            map.put("adapters", adapters.stream().map(AdapterStatus::toMap).toList());
            map.put("guardrails", guardrails);
            map.put("nonGoals", nonGoals);
            return map;
        }
    }

    public record AdapterStatus(String name, boolean present, String implementationClass, String role) {
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", name);
            map.put("present", present);
            map.put("implementationClass", implementationClass);
            map.put("role", role);
            return map;
        }
    }
}
