package com.nousresearch.hermes.evalset;

import com.nousresearch.hermes.config.Constants;
import com.nousresearch.hermes.scenario.ScenarioService;
import com.nousresearch.hermes.workspace.WorkspaceService;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Service for managing eval sets and running evaluations against blueprint versions. */
public class EvalSetService {
    private final FileEvalSetRepository repository;
    private final WorkspaceService workspaceService;
    private final ScenarioService scenarioService;

    public EvalSetService(WorkspaceService workspaceService, ScenarioService scenarioService) {
        this(new FileEvalSetRepository(Constants.getHermesHome().resolve("business/workspaces")), workspaceService, scenarioService);
    }

    public EvalSetService(Path workspacesRoot, WorkspaceService workspaceService, ScenarioService scenarioService) {
        this(new FileEvalSetRepository(workspacesRoot), workspaceService, scenarioService);
    }

    public EvalSetService(FileEvalSetRepository repository, WorkspaceService workspaceService, ScenarioService scenarioService) {
        this.repository = repository;
        this.workspaceService = workspaceService;
        this.scenarioService = scenarioService;
    }

    public EvalSetRecord createEvalSet(String workspaceId, String scenarioId, String evalSetId, String name, String description, List<EvalSetRecord.EvalCase> cases, Map<String, Object> metadata) {
        workspaceService.requireWorkspace(workspaceId);
        scenarioService.requireScenario(workspaceId, scenarioId);
        EvalSetRecord record = new EvalSetRecord()
            .setWorkspaceId(workspaceId)
            .setScenarioId(scenarioId)
            .setEvalSetId(evalSetId != null && !evalSetId.isBlank() ? evalSetId : "es-" + UUID.randomUUID().toString().substring(0, 8))
            .setName(name)
            .setDescription(description)
            .setCases(cases != null ? cases : List.of())
            .setMetadata(metadata);
        repository.save(record);
        return record;
    }

    public List<EvalSetRecord> listEvalSets(String workspaceId, String scenarioId) {
        workspaceService.requireWorkspace(workspaceId);
        return repository.listByScenario(workspaceId, scenarioId);
    }

    public EvalSetRecord getEvalSet(String workspaceId, String scenarioId, String evalSetId) {
        workspaceService.requireWorkspace(workspaceId);
        return repository.find(workspaceId, scenarioId, evalSetId)
            .orElseThrow(() -> new EvalSetNotFoundException(workspaceId, scenarioId, evalSetId));
    }

    public void deleteEvalSet(String workspaceId, String scenarioId, String evalSetId) {
        workspaceService.requireWorkspace(workspaceId);
        repository.delete(workspaceId, scenarioId, evalSetId);
    }

    public static class EvalSetNotFoundException extends RuntimeException {
        public EvalSetNotFoundException(String workspaceId, String scenarioId, String evalSetId) {
            super("EvalSet not found: " + evalSetId + " in scenario " + scenarioId + " / workspace " + workspaceId);
        }
    }
}
