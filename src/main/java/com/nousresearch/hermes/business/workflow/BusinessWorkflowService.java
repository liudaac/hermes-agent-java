package com.nousresearch.hermes.business.workflow;

import com.nousresearch.hermes.business.run.BusinessRunRecord;
import com.nousresearch.hermes.business.run.BusinessRunService;
import com.nousresearch.hermes.business.sla.SLADefinition;
import com.nousresearch.hermes.business.sla.SLAManager;
import com.nousresearch.hermes.org.workflow.Workflow;
import com.nousresearch.hermes.org.workflow.WorkflowEngine;
import com.nousresearch.hermes.org.workflow.WorkflowStep;
import com.nousresearch.hermes.scenario.ScenarioRecord;
import com.nousresearch.hermes.scenario.ScenarioService;
import com.nousresearch.hermes.workspace.WorkspaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Bridges Business Portal scenarios with the foundation WorkflowEngine.
 *
 * <p>Converts ScenarioRecords into Workflow instances with:
 * <ul>
 *   <li>DAG step dependency resolution</li>
 *   <li>Automatic SLA attachment</li>
 *   <li>Agent routing per step</li>
 *   <li>Human checkpoint integration</li>
 * </ul>
 */
public class BusinessWorkflowService {
    private static final Logger logger = LoggerFactory.getLogger(BusinessWorkflowService.class);

    private final WorkflowEngine workflowEngine;
    private final ScenarioService scenarioService;
    private final WorkspaceService workspaceService;
    private final BusinessRunService runService;
    private final SLAManager slaManager;

    public BusinessWorkflowService(WorkflowEngine workflowEngine, ScenarioService scenarioService,
                                    WorkspaceService workspaceService, BusinessRunService runService,
                                    SLAManager slaManager) {
        this.workflowEngine = workflowEngine;
        this.scenarioService = scenarioService;
        this.workspaceService = workspaceService;
        this.runService = runService;
        this.slaManager = slaManager;
    }

    /**
     * Create a workflow from a scenario definition.
     */
    public Workflow createWorkflowFromScenario(String workspaceId, String scenarioId, String userInput) {
        ScenarioRecord scenario = scenarioService.requireScenario(workspaceId, scenarioId);

        List<WorkflowStep> steps = buildStepsFromScenario(scenario, userInput);
        String workflowId = "wf-" + scenarioId + "-" + System.currentTimeMillis();

        Workflow workflow = new Workflow(
            workflowId,
            scenario.getName(),
            scenario.getDescription(),
            "business-portal",
            workspaceService.resolveTenantContext(workspaceId).getTenantId(),
            steps,
            true // saga mode for compensation
        );

        workflow.putContext("workspaceId", workspaceId);
        workflow.putContext("scenarioId", scenarioId);
        workflow.putContext("userInput", userInput);
        workflow.putContext("entryTeamId", scenario.getEntryTeamId());

        return workflow;
    }

    /**
     * Start a workflow and attach SLA monitoring.
     */
    public String startWorkflow(String workspaceId, Workflow workflow, SLADefinition sla) {
        workflowEngine.submit(workflow);

        // Create a business run to track the workflow
        BusinessRunRecord run = runService.createRun(
            workspaceId,
            String.valueOf(workflow.getContext().get("entryTeamId")),
            workflow.getName(),
            String.valueOf(workflow.getContext().get("scenarioId")),
            workflow.getName(),
            String.valueOf(workflow.getContext().get("userInput")),
            "Workflow started",
            "Workflow execution in progress",
            "Monitor workflow progress",
            "Monitoring",
            "Review workflow results when complete",
            BusinessRunService.RUNNING,
            "workflow://" + workflow.getId(),
            List.of(),
            Map.of(),
            Map.of("workflowId", workflow.getId())
        );

        // Attach SLA if provided
        if (sla != null) {
            slaManager.attachSLA(run.getRunId(), sla, Map.of(
                "workspaceId", workspaceId,
                "workflowId", workflow.getId()
            ));
        }

        logger.info("Workflow {} started for workspace {} with run {}", workflow.getId(), workspaceId, run.getRunId());
        return run.getRunId();
    }

    /**
     * Approve a human checkpoint in a workflow.
     */
    public void approveCheckpoint(String workflowId, String decision) {
        workflowEngine.resume(workflowId, decision);
        logger.info("Workflow {} resumed with decision: {}", workflowId, decision);
    }

    /**
     * Get workflow status mapped to business-friendly format.
     */
    public Map<String, Object> getWorkflowStatus(String workflowId) {
        Optional<Workflow> wf = workflowEngine.getWorkflow(workflowId);
        if (wf.isEmpty()) {
            return Map.of("found", false);
        }

        Workflow workflow = wf.get();
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("found", true);
        status.put("workflowId", workflow.getId());
        status.put("name", workflow.getName());
        status.put("status", workflow.getStatus().name());
        status.put("progress", workflow.getProgress());
        status.put("currentStep", workflow.getCurrentStep() != null ? workflow.getCurrentStep().getName() : null);
        status.put("stepsTotal", workflow.getSteps().size());
        status.put("stepsCompleted", workflow.getResults().size());
        status.put("waitingForHuman", workflow.needsHumanDecision());
        status.put("createdAt", workflow.getCreatedAt().toString());
        return status;
    }

    /**
     * List active workflows for a workspace.
     */
    public List<Map<String, Object>> listActiveWorkflows(String workspaceId) {
        return workflowEngine.listActive().stream()
            .filter(w -> workspaceId.equals(w.getContext().get("workspaceId")))
            .map(w -> getWorkflowStatus(w.getId()))
            .toList();
    }

    // ---- Step builders ----

    private List<WorkflowStep> buildStepsFromScenario(ScenarioRecord scenario, String userInput) {
        List<WorkflowStep> steps = new ArrayList<>();

        // Step 1: Intent classification
        steps.add(new WorkflowStep.Builder("classify", WorkflowStep.Type.SUB_AGENT)
            .target(scenario.getEntryTeamId())
            .param("task", "classify_intent")
            .param("input", userInput)
            .timeout(30_000)
            .build());

        // Step 2: Validation checkpoint (human approval for high-risk)
        steps.add(new WorkflowStep.Builder("validate", WorkflowStep.Type.HUMAN_CHECKPOINT)
            .target("business-operator")
            .humanCheckpoint("Review the classified intent and confirm execution", "approve", "reject", "modify")
            .timeout(300_000)
            .build());

        // Step 3: Execute via entry team
        steps.add(new WorkflowStep.Builder("execute", WorkflowStep.Type.SUB_AGENT)
            .target(scenario.getEntryTeamId())
            .param("task", "execute_scenario")
            .param("scenarioId", scenario.getScenarioId())
            .dependsOn("validate")
            .timeout(300_000)
            .retry(2, 5_000, true)
            .build());

        // Step 4: Quality review (if scenario has success criteria)
        if (scenario.getSuccessCriteria() != null && !scenario.getSuccessCriteria().isEmpty()) {
            steps.add(new WorkflowStep.Builder("quality_check", WorkflowStep.Type.SUB_AGENT)
                .target(scenario.getEntryTeamId())
                .param("task", "verify_success")
                .param("criteria", scenario.getSuccessCriteria())
                .dependsOn("execute")
                .timeout(60_000)
                .build());
        }

        // Step 5: Notify completion
        steps.add(new WorkflowStep.Builder("notify", WorkflowStep.Type.TOOL_CALL)
            .target("notification")
            .param("message", "Scenario " + scenario.getName() + " completed")
            .dependsOn("quality_check")
            .build());

        return steps;
    }
}
