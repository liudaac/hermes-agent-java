package com.nousresearch.hermes.business.foundation;

import com.nousresearch.hermes.blueprint.FoundationCapabilityValidator;
import com.nousresearch.hermes.blueprint.TeamBlueprintCompiler;
import com.nousresearch.hermes.business.approval.BusinessApprovalAdapter;
import com.nousresearch.hermes.business.insight.BusinessEvalRunProjectionAdapter;
import com.nousresearch.hermes.business.insight.BusinessInsightProjectionAdapter;
import com.nousresearch.hermes.business.run.BusinessRunProjectionAdapter;
import com.nousresearch.hermes.evolution.EvolutionProposalAdapter;
import com.nousresearch.hermes.prompt.PromptAssetResolver;
import com.nousresearch.hermes.prompt.PromptAssetService;
import com.nousresearch.hermes.scenario.ScenarioIntentAdapter;
import com.nousresearch.hermes.tenant.core.TenantManager;
import com.nousresearch.hermes.tenant.core.TenantManagerConfig;
import com.nousresearch.hermes.tools.ToolRegistry;
import com.nousresearch.hermes.workspace.WorkspaceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BusinessPortalFoundationDiagnosticsTest {
    @TempDir
    Path tempDir;

    @Test
    void reportsFacadeAdapterBaselineAndGuardrails() {
        Path businessRoot = tempDir.resolve("business/workspaces");
        TenantManager tenantManager = new TenantManager(tempDir.resolve("tenants"), new TenantManagerConfig());
        WorkspaceService workspaceService = new WorkspaceService(businessRoot, tenantManager);
        workspaceService.createWorkspace("customer-service", "客服业务空间", null, "ops", Map.of());
        PromptAssetService promptAssetService = new PromptAssetService(businessRoot, workspaceService);
        PromptAssetResolver promptResolver = new PromptAssetResolver(promptAssetService, workspaceService, tenantManager);
        FoundationCapabilityValidator validator = new FoundationCapabilityValidator(workspaceService, tenantManager, isolatedRegistry(), promptResolver);
        BusinessApprovalAdapter approvalAdapter = new BusinessApprovalAdapter();
        BusinessPortalFoundationFacade facade = new BusinessPortalFoundationFacade(
            promptResolver,
            validator,
            new TeamBlueprintCompiler(workspaceService, tenantManager, validator),
            new ScenarioIntentAdapter(workspaceService, tenantManager),
            new BusinessRunProjectionAdapter(),
            approvalAdapter,
            new BusinessInsightProjectionAdapter(),
            new BusinessEvalRunProjectionAdapter(),
            new EvolutionProposalAdapter(workspaceService, tenantManager, approvalAdapter)
        );

        var report = new BusinessPortalFoundationDiagnostics().inspect(facade);

        assertTrue(report.facadeReady());
        assertEquals("BusinessPortalFoundationFacade", report.boundary());
        assertEquals(10, report.adapters().size());
        assertTrue(report.adapters().stream().allMatch(BusinessPortalFoundationDiagnostics.AdapterStatus::present));
        assertTrue(report.guardrails().stream().anyMatch(guardrail -> guardrail.contains("BusinessPortalFoundationFacade")));
        assertTrue(report.nonGoals().contains("No generation API"));
        assertEquals(true, report.toMap().get("facadeReady"));
    }

    private ToolRegistry isolatedRegistry() {
        try {
            var constructor = ToolRegistry.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
