package com.nousresearch.hermes.business.foundation;

import com.nousresearch.hermes.blueprint.FoundationCapabilityValidator;
import com.nousresearch.hermes.blueprint.TeamBlueprintCompiler;
import com.nousresearch.hermes.business.approval.BusinessApprovalAdapter;
import com.nousresearch.hermes.business.run.BusinessRunProjectionAdapter;
import com.nousresearch.hermes.business.insight.BusinessInsightProjectionAdapter;
import com.nousresearch.hermes.evolution.EvolutionProposalAdapter;
import com.nousresearch.hermes.prompt.PromptAssetResolver;
import com.nousresearch.hermes.prompt.PromptAssetService;
import com.nousresearch.hermes.scenario.ScenarioIntentAdapter;
import com.nousresearch.hermes.tenant.core.TenantManager;
import com.nousresearch.hermes.tools.ToolRegistry;
import com.nousresearch.hermes.workspace.WorkspaceService;

import java.util.Objects;

/** Factory for the Business Portal foundation adapter facade. */
public class BusinessPortalAdapterRegistry {
    private final WorkspaceService workspaceService;
    private final TenantManager tenantManager;
    private final PromptAssetService promptAssetService;
    private final ToolRegistry toolRegistry;

    public BusinessPortalAdapterRegistry(WorkspaceService workspaceService,
                                         TenantManager tenantManager,
                                         PromptAssetService promptAssetService) {
        this(workspaceService, tenantManager, promptAssetService, ToolRegistry.getInstance());
    }

    public BusinessPortalAdapterRegistry(WorkspaceService workspaceService,
                                         TenantManager tenantManager,
                                         PromptAssetService promptAssetService,
                                         ToolRegistry toolRegistry) {
        this.workspaceService = Objects.requireNonNull(workspaceService, "workspaceService");
        this.tenantManager = Objects.requireNonNull(tenantManager, "tenantManager");
        this.promptAssetService = Objects.requireNonNull(promptAssetService, "promptAssetService");
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry");
    }

    public BusinessPortalFoundationFacade createFacade() {
        PromptAssetResolver promptResolver = new PromptAssetResolver(promptAssetService, workspaceService, tenantManager);
        FoundationCapabilityValidator validator = new FoundationCapabilityValidator(workspaceService, tenantManager, toolRegistry, promptResolver);
        TeamBlueprintCompiler compiler = new TeamBlueprintCompiler(workspaceService, tenantManager, validator);
        ScenarioIntentAdapter scenarioAdapter = new ScenarioIntentAdapter(workspaceService, tenantManager);
        BusinessRunProjectionAdapter runProjectionAdapter = new BusinessRunProjectionAdapter();
        BusinessApprovalAdapter approvalAdapter = new BusinessApprovalAdapter();
        BusinessInsightProjectionAdapter insightProjectionAdapter = new BusinessInsightProjectionAdapter();
        EvolutionProposalAdapter evolutionAdapter = new EvolutionProposalAdapter(workspaceService, tenantManager, approvalAdapter);
        return new BusinessPortalFoundationFacade(
            promptResolver,
            validator,
            compiler,
            scenarioAdapter,
            runProjectionAdapter,
            approvalAdapter,
            insightProjectionAdapter,
            evolutionAdapter
        );
    }
}
