package com.nousresearch.hermes.scenario;

import com.nousresearch.hermes.blueprint.TeamBlueprintRuntime;
import com.nousresearch.hermes.blueprint.TeamBlueprintService;
import com.nousresearch.hermes.business.approval.BusinessApprovalRecord;
import com.nousresearch.hermes.business.approval.BusinessApprovalService;
import com.nousresearch.hermes.business.run.BusinessRunProjectionAdapter;
import com.nousresearch.hermes.business.run.BusinessRunRecord;
import com.nousresearch.hermes.business.run.BusinessRunService;
import com.nousresearch.hermes.collaboration.IntentOrchestrator;
import com.nousresearch.hermes.config.Constants;
import com.nousresearch.hermes.memory.ActiveMemoryService;
import com.nousresearch.hermes.policy.PolicyService;
import com.nousresearch.hermes.workspace.WorkspaceService;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Business service for scenarios. */
public class ScenarioService {
    private final FileScenarioRepository repository;
    private final WorkspaceService workspaceService;
    private final TeamBlueprintService teamBlueprintService;
    private final TeamBlueprintRuntime teamBlueprintRuntime;
    private ScenarioIntentAdapter scenarioIntentAdapter;
    private PolicyService policyService;
    private BusinessApprovalService businessApprovalService;
    private ActiveMemoryService activeMemoryService;

    public ScenarioService(WorkspaceService workspaceService, TeamBlueprintService teamBlueprintService) {
        this(new FileScenarioRepository(Constants.getHermesHome().resolve("business/workspaces")), workspaceService, teamBlueprintService);
    }

    public ScenarioService(Path workspacesRoot, WorkspaceService workspaceService, TeamBlueprintService teamBlueprintService) {
        this(new FileScenarioRepository(workspacesRoot), workspaceService, teamBlueprintService);
    }

    public ScenarioService(FileScenarioRepository repository, WorkspaceService workspaceService, TeamBlueprintService teamBlueprintService) {
        this.repository = repository;
        this.workspaceService = workspaceService;
        this.teamBlueprintService = teamBlueprintService;
        this.teamBlueprintRuntime = new TeamBlueprintRuntime(workspaceService, teamBlueprintService);
    }

    /** Wire the intent adapter after construction (breaks circular dependency with TenantManager). */
    public void setScenarioIntentAdapter(ScenarioIntentAdapter adapter) {
        this.scenarioIntentAdapter = adapter;
    }

    /** Wire policy and approval services for automatic approval gating. */
    public void setPolicyService(PolicyService policyService, BusinessApprovalService businessApprovalService) {
        this.policyService = policyService;
        this.businessApprovalService = businessApprovalService;
    }

    /** Wire active memory service for knowledge recall. */
    public void setActiveMemoryService(ActiveMemoryService activeMemoryService) {
        this.activeMemoryService = activeMemoryService;
    }

    public ScenarioRecord createScenario(String workspaceId, String scenarioId, String name, String description,
                                         String entryTeamId, List<String> successCriteria,
                                         List<String> approvalRules, Map<String, Object> metadata) {
        workspaceService.requireWorkspace(workspaceId);
        validateId(scenarioId, "scenarioId");
        if (repository.exists(workspaceId, scenarioId)) {
            throw new ScenarioAlreadyExistsException(workspaceId, scenarioId);
        }
        if (entryTeamId != null && !entryTeamId.isBlank()) {
            teamBlueprintService.requireTeamBlueprint(workspaceId, entryTeamId);
        }
        Instant now = Instant.now();
        ScenarioRecord record = new ScenarioRecord()
            .setWorkspaceId(workspaceId)
            .setScenarioId(scenarioId)
            .setName(name != null && !name.isBlank() ? name : scenarioId)
            .setDescription(description)
            .setEntryTeamId(entryTeamId)
            .setStatus("ACTIVE")
            .setSuccessCriteria(successCriteria)
            .setApprovalRules(approvalRules)
            .setMetadata(metadata)
            .setCreatedAt(now)
            .setUpdatedAt(now);
        repository.save(record);
        return record;
    }

    public List<ScenarioRecord> listScenarios(String workspaceId) {
        workspaceService.requireWorkspace(workspaceId);
        return repository.list(workspaceId);
    }

    public Optional<ScenarioRecord> getScenario(String workspaceId, String scenarioId) {
        return repository.findById(workspaceId, scenarioId);
    }

    public ScenarioRecord requireScenario(String workspaceId, String scenarioId) {
        workspaceService.requireWorkspace(workspaceId);
        return getScenario(workspaceId, scenarioId).orElseThrow(() -> new ScenarioNotFoundException(workspaceId, scenarioId));
    }

    public BusinessRunRecord executeScenario(String workspaceId, String scenarioId, String userInput,
                                               BusinessRunService runService) {
        return executeScenario(workspaceId, scenarioId, userInput, runService, false);
    }

    /**
     * Execute a scenario through the IntentOrchestrator and project the result into a BusinessRunRecord.
     * Returns the business run story with real execution traces.
     *
     * <p>If policy service is wired and approval rules are triggered, creates an approval card
     * and throws {@link ApprovalRequiredException} instead of executing.</p>
     *
     * @param skipApprovalCheck if true, bypasses the approval gate (used for resume-execution after approval)
     */
    public BusinessRunRecord executeScenario(String workspaceId, String scenarioId, String userInput,
                                               BusinessRunService runService, boolean skipApprovalCheck) {
        if (scenarioIntentAdapter == null) {
            throw new IllegalStateException("ScenarioIntentAdapter not wired — cannot execute scenario");
        }
        ScenarioRecord scenario = requireScenario(workspaceId, scenarioId);

        // Ensure the team blueprint has running agent instances on the tenant bus
        String entryTeamId = scenario.getEntryTeamId();
        if (entryTeamId != null && !entryTeamId.isBlank()) {
            teamBlueprintRuntime.ensureTeamRuntime(workspaceId, entryTeamId);
        }

        // Approval gate: check if any agent's approval rules require human review
        if (!skipApprovalCheck && policyService != null && businessApprovalService != null) {
            var check = policyService.checkApprovalRequired(workspaceId, scenario.getEntryTeamId(), "execute", userInput);
            if (check.approvalNeeded()) {
                BusinessApprovalRecord approval = businessApprovalService.createApproval(
                    workspaceId,
                    scenario.getEntryTeamId(),
                    "Scenario execution requires approval",
                    "Action: execute scenario '" + scenario.getName() + "'\nInput: " + userInput,
                    "Please confirm this scenario execution is authorized.",
                    "Execution proceeds with full Agent Runtime.",
                    "Execution is blocked.",
                    "Review input for policy compliance.",
                    "MEDIUM",
                    Map.of("scenarioId", scenarioId, "agentId", check.agentId(), "matchedRule", check.matchedRule(), "userInput", userInput),
                    Map.of("source", "auto", "trigger", check.reason())
                );
                throw new ApprovalRequiredException(approval.getApprovalId(), check.reason());
            }
        }

        IntentOrchestrator.IntentRun intentRun = scenarioIntentAdapter.execute(scenario, userInput);
        BusinessRunProjectionAdapter projectionAdapter = new BusinessRunProjectionAdapter();
        BusinessRunRecord projection = projectionAdapter.fromIntentRun(
            workspaceId, scenarioId, scenario.getName(), intentRun);

        // Active Memory: recall relevant knowledge and attach to run metadata
        if (activeMemoryService != null) {
            var recalledMemories = activeMemoryService.recall(workspaceId, scenarioId, scenario.getSuccessCriteria(), userInput, 5);
            if (!recalledMemories.isEmpty()) {
                Map<String, Object> memoryMeta = new LinkedHashMap<>();
                memoryMeta.put("recalledCount", recalledMemories.size());
                memoryMeta.put("memories", recalledMemories.stream()
                    .map(m -> Map.of("memoryId", m.getMemoryId(), "title", m.getTitle(), "type", m.getType(), "content", m.getContent()))
                    .toList());
                Map<String, Object> mergedMeta = new LinkedHashMap<>(projection.getMetadata() != null ? projection.getMetadata() : Map.of());
                mergedMeta.put("activeMemory", memoryMeta);
                projection.setMetadata(mergedMeta);
            }
        }

        return projectionAdapter.persistProjection(runService, projection);
    }

    public static class ApprovalRequiredException extends RuntimeException {
        private final String approvalId;
        public ApprovalRequiredException(String approvalId, String reason) {
            super("Approval required: " + approvalId + " — " + reason);
            this.approvalId = approvalId;
        }
        public String getApprovalId() { return approvalId; }
    }

    private static void validateId(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        if (!value.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{1,63}")) {
            throw new IllegalArgumentException(field + " must be 2-64 chars and contain only letters, numbers, dot, underscore or dash");
        }
    }

    public static class ScenarioAlreadyExistsException extends RuntimeException {
        public ScenarioAlreadyExistsException(String workspaceId, String scenarioId) { super("Scenario already exists: " + workspaceId + "/" + scenarioId); }
    }

    public static class ScenarioNotFoundException extends RuntimeException {
        public ScenarioNotFoundException(String workspaceId, String scenarioId) { super("Scenario not found: " + workspaceId + "/" + scenarioId); }
    }
}
