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
 * 业务工作流服务 — 将 Business Portal 的 Scenario 与底层 WorkflowEngine 打通。
 *
 * <p>核心能力：
 * <ul>
 *   <li>Scenario → Workflow 自动转换</li>
 *   <li>DAG 步骤依赖解析</li>
 *   <li>自动 SLA 挂载</li>
 *   <li>每步 Agent 路由</li>
 *   <li>人工检查点（Human Checkpoint）集成</li>
 * </ul>
 * <p>工作流启动后，前端通过 SSE 实时接收状态变更，人工可在检查点审批/拒绝。</p>
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
     * 根据 Scenario 定义创建 Workflow 实例。
     * 自动构建 classify → validate → execute → quality_check → notify 的标准流程。
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

        // 若提供了 SLA 定义，则挂载到本次运行
        if (sla != null) {
            slaManager.attachSLA(run.getRunId(), sla, Map.of(
                "workspaceId", workspaceId,
                "workflowId", workflow.getId()
            ));
        }

        logger.info("Workflow {} started for workspace {} with run {}", workflow.getId(), workspaceId, run.getRunId());
        return run.getRunId();
    }

    /** 审批工作流中的人工检查点（Human Checkpoint） */
    public void approveCheckpoint(String workflowId, String decision) {
        workflowEngine.resume(workflowId, decision);
        logger.info("Workflow {} resumed with decision: {}", workflowId, decision);
    }

    /** 获取工作流状态，转换为业务友好的格式 */
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

    // ---- 步骤构建器：将 Scenario 转换为标准 5 步工作流 ----

    /**
     * 根据 Scenario 定义构建标准工作流步骤：
     * 1. classify — Agent 分类意图
     * 2. validate — 人工检查点（高风险时暂停等待审批）
     * 3. execute — 通过入口团队执行场景
     * 4. quality_check — 质量审核（若场景定义了成功标准）
     * 5. notify — 通知完成
     */
    private List<WorkflowStep> buildStepsFromScenario(ScenarioRecord scenario, String userInput) {
        List<WorkflowStep> steps = new ArrayList<>();

        // Step 1: 意图分类
        steps.add(new WorkflowStep.Builder("classify", WorkflowStep.Type.SUB_AGENT)
            .target(scenario.getEntryTeamId())
            .param("task", "classify_intent")
            .param("input", userInput)
            .timeout(30_000)
            .build());

        // Step 2: 验证检查点（高风险场景需人工审批）
        steps.add(new WorkflowStep.Builder("validate", WorkflowStep.Type.HUMAN_CHECKPOINT)
            .target("business-operator")
            .humanCheckpoint("Review the classified intent and confirm execution", "approve", "reject", "modify")
            .timeout(300_000)
            .build());

        // Step 3: 通过入口团队执行
        steps.add(new WorkflowStep.Builder("execute", WorkflowStep.Type.SUB_AGENT)
            .target(scenario.getEntryTeamId())
            .param("task", "execute_scenario")
            .param("scenarioId", scenario.getScenarioId())
            .dependsOn("validate")
            .timeout(300_000)
            .retry(2, 5_000, true)
            .build());

        // Step 4: 质量审核（若场景定义了成功标准）
        if (scenario.getSuccessCriteria() != null && !scenario.getSuccessCriteria().isEmpty()) {
            steps.add(new WorkflowStep.Builder("quality_check", WorkflowStep.Type.SUB_AGENT)
                .target(scenario.getEntryTeamId())
                .param("task", "verify_success")
                .param("criteria", scenario.getSuccessCriteria())
                .dependsOn("execute")
                .timeout(60_000)
                .build());
        }

        // Step 5: 完成通知
        steps.add(new WorkflowStep.Builder("notify", WorkflowStep.Type.TOOL_CALL)
            .target("notification")
            .param("message", "Scenario " + scenario.getName() + " completed")
            .dependsOn("quality_check")
            .build());

        return steps;
    }
}
