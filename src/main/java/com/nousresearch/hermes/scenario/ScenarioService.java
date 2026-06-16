package com.nousresearch.hermes.scenario;

import com.nousresearch.hermes.config.Constants;
import com.nousresearch.hermes.workspace.WorkspaceService;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Business service for scenarios. */
public class ScenarioService {
    private final FileScenarioRepository repository;
    private final WorkspaceService workspaceService;

    public ScenarioService(WorkspaceService workspaceService) {
        this(new FileScenarioRepository(Constants.getHermesHome().resolve("business/workspaces")), workspaceService);
    }

    public ScenarioService(Path workspacesRoot, WorkspaceService workspaceService) {
        this(new FileScenarioRepository(workspacesRoot), workspaceService);
    }

    public ScenarioService(FileScenarioRepository repository, WorkspaceService workspaceService) {
        this.repository = repository;
        this.workspaceService = workspaceService;
    }

    public ScenarioRecord createScenario(String workspaceId, String scenarioId, String name, String description,
                                         String entryTeamId, List<String> successCriteria,
                                         List<String> approvalRules, Map<String, Object> metadata) {
        workspaceService.requireWorkspace(workspaceId);
        validateId(scenarioId, "scenarioId");
        if (repository.exists(workspaceId, scenarioId)) {
            throw new ScenarioAlreadyExistsException(workspaceId, scenarioId);
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
