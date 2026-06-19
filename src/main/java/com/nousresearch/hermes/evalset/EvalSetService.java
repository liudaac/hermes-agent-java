package com.nousresearch.hermes.evalset;

import com.nousresearch.hermes.business.run.BusinessRunRecord;
import com.nousresearch.hermes.business.run.BusinessRunService;
import com.nousresearch.hermes.config.Constants;
import com.nousresearch.hermes.scenario.ScenarioService;
import com.nousresearch.hermes.workspace.WorkspaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Service for managing eval sets and running evaluations against blueprint versions. */
public class EvalSetService {
    private static final Logger logger = LoggerFactory.getLogger(EvalSetService.class);

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

    /**
     * Run all cases in an eval set against the current active team version.
     *
     * <p>Executes each case as a scenario run and collects results.
     * Returns a report with pass/fail counts and per-case details.</p>
     */
    public EvalResult runEvaluation(String workspaceId, String scenarioId, String evalSetId,
                                     BusinessRunService runService) {
        workspaceService.requireWorkspace(workspaceId);

        EvalSetRecord evalSet = repository.find(workspaceId, scenarioId, evalSetId)
            .orElseThrow(() -> new EvalSetNotFoundException(workspaceId, scenarioId, evalSetId));

        List<EvalResult.CaseResult> caseResults = new ArrayList<>();
        int passed = 0;
        int failed = 0;

        for (var evalCase : evalSet.getCases()) {
            try {
                BusinessRunRecord run = scenarioService.executeScenario(
                    workspaceId, scenarioId, evalCase.getInput(), runService, true);

                String finalStatus = waitForRunCompletion(runService, workspaceId, run.getRunId(), 60_000);
                boolean passedCase = "COMPLETED".equals(finalStatus) &&
                    checkPassCriteria(run, evalCase);

                if (passedCase) passed++; else failed++;

                caseResults.add(new EvalResult.CaseResult(
                    evalCase.getCaseId(),
                    evalCase.getName(),
                    passedCase ? "PASSED" : "FAILED",
                    finalStatus,
                    run.getRunId(),
                    run.getResultSummary(),
                    passedCase ? null : "Result did not match expected criteria"
                ));
            } catch (Exception e) {
                failed++;
                caseResults.add(new EvalResult.CaseResult(
                    evalCase.getCaseId(),
                    evalCase.getName(),
                    "ERROR",
                    "ERROR",
                    null,
                    null,
                    e.getMessage()
                ));
            }
        }

        return new EvalResult(
            "evr-" + UUID.randomUUID().toString().substring(0, 8),
            evalSetId,
            scenarioId,
            passed, failed, caseResults.size(),
            caseResults,
            Instant.now()
        );
    }

    /**
     * Run evaluation against a specific team blueprint draft version.
     * Activates the draft version, runs eval, then rolls back to original active version.
     *
     * <p>Used for evaluating evolution proposals before approving them.</p>
     */
    public EvalResult runEvaluationForDraftVersion(String workspaceId, String evalSetId,
                                                     String scenarioId, String teamId, int draftVersion,
                                                     BusinessRunService runService,
                                                     com.nousresearch.hermes.blueprint.TeamBlueprintService teamBlueprintService) {
        var blueprint = teamBlueprintService.requireTeamBlueprint(workspaceId, teamId);
        int originalActiveVersion = blueprint.getActiveVersion();

        try {
            teamBlueprintService.activateVersion(workspaceId, teamId, draftVersion);
            logger.info("Activated draft version v{} for team {}/{} (eval mode)", draftVersion, workspaceId, teamId);
            return runEvaluation(workspaceId, scenarioId, evalSetId, runService);
        } finally {
            try {
                teamBlueprintService.activateVersion(workspaceId, teamId, originalActiveVersion);
                logger.info("Rolled back team {}/{} to v{}", workspaceId, teamId, originalActiveVersion);
            } catch (Exception e) {
                logger.error("Failed to roll back team version: {}", e.getMessage());
            }
        }
    }

    private String waitForRunCompletion(BusinessRunService runService, String workspaceId,
                                        String runId, long timeoutMs) {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeoutMs) {
            try {
                BusinessRunRecord run = runService.requireRun(workspaceId, runId);
                String status = run.getStatus();
                if ("COMPLETED".equals(status) || "FAILED".equals(status) || "NEEDS_APPROVAL".equals(status)) {
                    return status;
                }
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "FAILED";
            } catch (Exception e) {
                return "FAILED";
            }
        }
        return "TIMEOUT";
    }

    private boolean checkPassCriteria(BusinessRunRecord run, EvalSetRecord.EvalCase evalCase) {
        if (!"COMPLETED".equals(run.getStatus())) {
            return false;
        }
        // Check expected output keywords
        if (evalCase.getExpectedOutput() != null && !evalCase.getExpectedOutput().isBlank()) {
            String result = run.getResultSummary() != null ? run.getResultSummary().toLowerCase() : "";
            String expected = evalCase.getExpectedOutput().toLowerCase();
            for (String keyword : expected.split("[,，\\s]+")) {
                if (!keyword.isBlank() && !result.contains(keyword)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Result of running an eval set. */
    public record EvalResult(
        String evalRunId,
        String evalSetId,
        String scenarioId,
        int passed,
        int failed,
        int total,
        List<CaseResult> cases,
        Instant completedAt
    ) {
        public double passRate() {
            return total > 0 ? (double) passed / total : 0.0;
        }

        public record CaseResult(
            String caseId,
            String caseName,
            String status,
            String runStatus,
            String runId,
            String actualOutput,
            String failureReason
        ) {}
    }

    public static class EvalSetNotFoundException extends RuntimeException {
        public EvalSetNotFoundException(String workspaceId, String scenarioId, String evalSetId) {
            super("EvalSet not found: " + evalSetId + " in scenario " + scenarioId + " / workspace " + workspaceId);
        }
    }
}
