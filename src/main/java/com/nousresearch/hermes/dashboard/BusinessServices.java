package com.nousresearch.hermes.dashboard;

import com.nousresearch.hermes.business.analytics.ApprovalAnalytics;
import com.nousresearch.hermes.business.approval.BusinessApprovalService;
import com.nousresearch.hermes.business.approval.ToolApprovalCoordinator;
import com.nousresearch.hermes.business.dlq.DeadLetterQueue;
import com.nousresearch.hermes.business.event.BusinessEventBus;
import com.nousresearch.hermes.business.foundation.BusinessPortalAdapterRegistry;
import com.nousresearch.hermes.business.foundation.BusinessPortalFoundationFacade;
import com.nousresearch.hermes.business.humanintheloop.HumanOverrideService;
import com.nousresearch.hermes.business.insight.BusinessInsightService;
import com.nousresearch.hermes.business.run.BusinessRunService;
import com.nousresearch.hermes.business.sla.SLAManager;
import com.nousresearch.hermes.business.template.BusinessTemplateService;
import com.nousresearch.hermes.business.template.TemplateCloneService;
import com.nousresearch.hermes.business.vertical.ecommerce.EcommerceScenarioFactory;
import com.nousresearch.hermes.business.workflow.BusinessWorkflowService;
import com.nousresearch.hermes.blueprint.QuickTeamBuilderService;
import com.nousresearch.hermes.blueprint.TeamBlueprintService;
import com.nousresearch.hermes.canary.CanaryReleaseService;
import com.nousresearch.hermes.config.HermesConfig;
import com.nousresearch.hermes.connector.ConnectorRegistry;
import com.nousresearch.hermes.dashboard.jarvis.ApprovalBridge;
import com.nousresearch.hermes.dashboard.jarvis.ChatService;
import com.nousresearch.hermes.dashboard.jarvis.IntentRouter;
import com.nousresearch.hermes.dashboard.jarvis.JarvisHandler;
import com.nousresearch.hermes.dashboard.jarvis.ProductQueryService;
import com.nousresearch.hermes.evalset.EvalSetService;
import com.nousresearch.hermes.evolution.EvolutionProposalService;
import com.nousresearch.hermes.memory.BusinessMemoryNoteService;
import com.nousresearch.hermes.model.ModelClient;
import com.nousresearch.hermes.org.workflow.WorkflowEngine;
import com.nousresearch.hermes.policy.PolicyService;
import com.nousresearch.hermes.prompt.PromptAssetService;
import com.nousresearch.hermes.scenario.PlanReflectionService;
import com.nousresearch.hermes.scenario.ScenarioIntentAdapter;
import com.nousresearch.hermes.scenario.ScenarioService;
import com.nousresearch.hermes.tenant.core.TenantManager;
import com.nousresearch.hermes.tenant.metrics.MetricsCollector;
import com.nousresearch.hermes.workspace.WorkspaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Container for all business-layer services constructed during
 * {@link DashboardServer} startup.
 *
 * <p>Extracted from the 130-line constructor into a dedicated holder so
 * that the DashboardServer constructor only orchestrates lifecycle (init
 * → start → stop) instead of also being a service-factory. Adding a new
 * business service goes into {@link #build(HermesConfig, TenantManager)}
 * instead of being another {@code new ...} block in the middle of the
 * constructor.</p>
 */
final class BusinessServices {
    private static final Logger logger = LoggerFactory.getLogger(BusinessServices.class);

    final WorkspaceService workspaceService;
    final TeamBlueprintService teamBlueprintService;
    final ScenarioService scenarioService;
    final BusinessApprovalService businessApprovalService;
    final BusinessRunService businessRunService;
    final BusinessInsightService businessInsightService;
    final PromptAssetService promptAssetService;
    final EvolutionProposalService evolutionProposalService;
    final PolicyService policyService;
    final BusinessTemplateService businessTemplateService;
    final TemplateCloneService templateCloneService;
    final EvalSetService evalSetService;
    final CanaryReleaseService canaryReleaseService;
    final BusinessMemoryNoteService activeMemoryService;
    final BusinessPortalFoundationFacade businessPortalFoundationFacade;
    final SLAManager slaManager;
    final DeadLetterQueue deadLetterQueue;
    final ApprovalAnalytics approvalAnalytics;
    final HumanOverrideService humanOverrideService;
    final BusinessWorkflowService workflowService;
    final ConnectorRegistry connectorRegistry;
    final EcommerceScenarioFactory ecommerceScenarioFactory;
    final BusinessEventBus businessEventBus;
    final ToolApprovalCoordinator toolApprovalCoordinator;
    final QuickTeamBuilderService quickTeamBuilderService;
    final JarvisHandler jarvisHandler;
    final MetricsCollector metricsCollector;

    private BusinessServices(Builder b) {
        this.workspaceService = b.workspaceService;
        this.teamBlueprintService = b.teamBlueprintService;
        this.scenarioService = b.scenarioService;
        this.businessApprovalService = b.businessApprovalService;
        this.businessRunService = b.businessRunService;
        this.businessInsightService = b.businessInsightService;
        this.promptAssetService = b.promptAssetService;
        this.evolutionProposalService = b.evolutionProposalService;
        this.policyService = b.policyService;
        this.businessTemplateService = b.businessTemplateService;
        this.templateCloneService = b.templateCloneService;
        this.evalSetService = b.evalSetService;
        this.canaryReleaseService = b.canaryReleaseService;
        this.activeMemoryService = b.activeMemoryService;
        this.businessPortalFoundationFacade = b.businessPortalFoundationFacade;
        this.slaManager = b.slaManager;
        this.deadLetterQueue = b.deadLetterQueue;
        this.approvalAnalytics = b.approvalAnalytics;
        this.humanOverrideService = b.humanOverrideService;
        this.workflowService = b.workflowService;
        this.connectorRegistry = b.connectorRegistry;
        this.ecommerceScenarioFactory = b.ecommerceScenarioFactory;
        this.businessEventBus = b.businessEventBus;
        this.toolApprovalCoordinator = b.toolApprovalCoordinator;
        this.quickTeamBuilderService = b.quickTeamBuilderService;
        this.jarvisHandler = b.jarvisHandler;
        this.metricsCollector = b.metricsCollector;
    }

    /**
     * Build the entire business service graph. Kept package-private; only
     * DashboardServer calls this during startup.
     */
    static BusinessServices build(HermesConfig config, TenantManager tenantManager) {
        Builder b = new Builder();

        // ── Core domain services ──────────────────────────────
        b.workspaceService = new WorkspaceService(tenantManager);
        b.teamBlueprintService = new TeamBlueprintService(b.workspaceService);
        b.scenarioService = new ScenarioService(b.workspaceService, b.teamBlueprintService);
        b.scenarioService.setScenarioIntentAdapter(new ScenarioIntentAdapter(b.workspaceService, tenantManager));
        b.businessApprovalService = new BusinessApprovalService(b.workspaceService);
        b.businessRunService = new BusinessRunService(b.workspaceService, b.teamBlueprintService, b.scenarioService);
        b.businessInsightService = new BusinessInsightService(b.workspaceService, b.teamBlueprintService, b.businessRunService, b.businessApprovalService);
        b.promptAssetService = new PromptAssetService(b.workspaceService);
        b.evolutionProposalService = new EvolutionProposalService(b.workspaceService, b.teamBlueprintService);
        b.policyService = new PolicyService(b.workspaceService, b.teamBlueprintService);
        b.businessTemplateService = new BusinessTemplateService();

        // ── Scenario service wiring ────────────────────────────
        b.scenarioService.setPolicyService(b.policyService, b.businessApprovalService);
        b.scenarioService.setBusinessMemoryNoteService(new BusinessMemoryNoteService(b.workspaceService));

        // ── Tool approval coordinator ──────────────────────────
        b.toolApprovalCoordinator = new ToolApprovalCoordinator(b.workspaceService, b.businessApprovalService);
        b.scenarioService.getTeamBlueprintRuntime().setToolApprovalCoordinator(b.toolApprovalCoordinator);

        b.evalSetService = new EvalSetService(b.workspaceService, b.scenarioService);
        b.canaryReleaseService = new CanaryReleaseService(b.workspaceService, b.teamBlueprintService);
        b.scenarioService.setCanaryReleaseService(b.canaryReleaseService);

        ModelClient modelClient = new ModelClient(config.getModelConfig());
        b.scenarioService.setPlanReflectionService(new PlanReflectionService(modelClient));
        b.quickTeamBuilderService = new QuickTeamBuilderService(modelClient);

        b.templateCloneService = new TemplateCloneService(
            b.businessTemplateService, b.workspaceService, b.promptAssetService,
            b.teamBlueprintService, b.scenarioService);
        b.activeMemoryService = new BusinessMemoryNoteService(b.workspaceService);
        b.businessPortalFoundationFacade = new BusinessPortalAdapterRegistry(
            b.workspaceService, tenantManager, b.promptAssetService).createFacade();

        // ── Event bus + extended orchestration ────────────────
        b.businessEventBus = new BusinessEventBus();
        b.slaManager = new SLAManager(b.businessRunService, b.businessApprovalService);
        b.deadLetterQueue = new DeadLetterQueue();
        b.approvalAnalytics = new ApprovalAnalytics(b.businessApprovalService);
        b.humanOverrideService = new HumanOverrideService(b.workspaceService, b.businessRunService);
        Path workflowsDir = Paths.get(System.getProperty("user.home"), ".hermes", "workflows");
        b.workflowService = new BusinessWorkflowService(
            new WorkflowEngine(workflowsDir), b.scenarioService, b.workspaceService,
            b.businessRunService, b.slaManager);
        b.connectorRegistry = new ConnectorRegistry();
        b.ecommerceScenarioFactory = new EcommerceScenarioFactory(
            b.workspaceService, b.scenarioService, b.teamBlueprintService);

        b.slaManager.setEventBus(b.businessEventBus);
        b.humanOverrideService.setEventBus(b.businessEventBus);
        b.deadLetterQueue.setEventBus(b.businessEventBus);

        // Bridge RunEventBus → BusinessEventBus
        b.businessRunService.getEventBus().subscribeGlobal(event -> {
            String status = switch (event.type()) {
                case RUN_STARTED -> "STARTED";
                case RUN_COMPLETED -> "COMPLETED";
                case RUN_FAILED -> "FAILED";
                case RUN_NEEDS_APPROVAL -> "NEEDS_APPROVAL";
                case STEP_STARTED, STEP_COMPLETED, STEP_FAILED, STEP_UPDATED -> "STEP";
            };
            String msg = event.message() != null ? event.message() : "";
            b.businessEventBus.runStatus(event.workspaceId(), event.runId(), status, msg);
        });

        // ── Jarvis ─────────────────────────────────────────────
        b.jarvisHandler = new JarvisHandler(
            new ChatService(config, tenantManager, b.toolApprovalCoordinator, b.businessApprovalService),
            new IntentRouter(modelClient, new ProductQueryService(
                modelClient, b.businessApprovalService, b.businessRunService,
                b.teamBlueprintService, tenantManager, b.deadLetterQueue)),
            new ApprovalBridge(b.businessApprovalService, b.toolApprovalCoordinator),
            b.businessEventBus,
            b.businessApprovalService
        );

        // ── Metrics collector ─────────────────────────────────
        b.metricsCollector = new MetricsCollector(tenantManager);

        logger.info("Business services initialized (workspace={}, scenario={}, run={}, approval={})",
            b.workspaceService != null, b.scenarioService != null,
            b.businessRunService != null, b.businessApprovalService != null);
        return new BusinessServices(b);
    }

    /** Stop any services that hold background threads. */
    void shutdown() {
        try { metricsCollector.stop(); } catch (Exception ignored) {}
    }

    private static final class Builder {
        WorkspaceService workspaceService;
        TeamBlueprintService teamBlueprintService;
        ScenarioService scenarioService;
        BusinessApprovalService businessApprovalService;
        BusinessRunService businessRunService;
        BusinessInsightService businessInsightService;
        PromptAssetService promptAssetService;
        EvolutionProposalService evolutionProposalService;
        PolicyService policyService;
        BusinessTemplateService businessTemplateService;
        TemplateCloneService templateCloneService;
        EvalSetService evalSetService;
        CanaryReleaseService canaryReleaseService;
        BusinessMemoryNoteService activeMemoryService;
        BusinessPortalFoundationFacade businessPortalFoundationFacade;
        SLAManager slaManager;
        DeadLetterQueue deadLetterQueue;
        ApprovalAnalytics approvalAnalytics;
        HumanOverrideService humanOverrideService;
        BusinessWorkflowService workflowService;
        ConnectorRegistry connectorRegistry;
        EcommerceScenarioFactory ecommerceScenarioFactory;
        BusinessEventBus businessEventBus;
        ToolApprovalCoordinator toolApprovalCoordinator;
        QuickTeamBuilderService quickTeamBuilderService;
        JarvisHandler jarvisHandler;
        MetricsCollector metricsCollector;
    }
}
