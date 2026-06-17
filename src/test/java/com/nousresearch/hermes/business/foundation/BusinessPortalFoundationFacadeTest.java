package com.nousresearch.hermes.business.foundation;

import com.nousresearch.hermes.approval.ToolRisk;
import com.nousresearch.hermes.blueprint.AgentBlueprintRecord;
import com.nousresearch.hermes.blueprint.TeamBlueprintRecord;
import com.nousresearch.hermes.blueprint.TeamBlueprintService;
import com.nousresearch.hermes.business.run.BusinessRunRecord;
import com.nousresearch.hermes.collaboration.IntentOrchestrator;
import com.nousresearch.hermes.prompt.PromptAssetResolver;
import com.nousresearch.hermes.prompt.PromptAssetService;
import com.nousresearch.hermes.scenario.ScenarioRecord;
import com.nousresearch.hermes.tenant.core.TenantManager;
import com.nousresearch.hermes.tenant.core.TenantManagerConfig;
import com.nousresearch.hermes.tools.ToolEntry;
import com.nousresearch.hermes.tools.ToolRegistry;
import com.nousresearch.hermes.workspace.WorkspaceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BusinessPortalFoundationFacadeTest {
    @TempDir
    Path tempDir;

    @Test
    void facadeComposesAdaptersWithoutAddingProductApi() {
        Path businessRoot = tempDir.resolve("business/workspaces");
        TenantManager tenantManager = new TenantManager(tempDir.resolve("tenants"), new TenantManagerConfig());
        WorkspaceService workspaceService = new WorkspaceService(businessRoot, tenantManager);
        workspaceService.createWorkspace("customer-service", "客服业务空间", null, "ops", Map.of());

        PromptAssetService promptAssetService = new PromptAssetService(businessRoot, workspaceService);
        promptAssetService.createPromptAsset("customer-service", "base", "Base", "purpose", "prompt", List.of(), Map.of());

        ToolRegistry registry = isolatedRegistry();
        registry.register(new ToolEntry.Builder()
            .name("order.query")
            .toolset("business")
            .schema(Map.of("description", "Query order"))
            .handler(args -> "{}")
            .risk(ToolRisk.LOW)
            .build());

        BusinessPortalFoundationFacade facade = new BusinessPortalAdapterRegistry(workspaceService, tenantManager, promptAssetService, registry).createFacade();

        assertTrue(facade.resolvePromptContext("customer-service", List.of("prompt://base"), "refund", PromptAssetResolver.ResolveOptions.promptOnly())
            .render().contains("prompt"));

        TeamBlueprintService teamBlueprintService = new TeamBlueprintService(businessRoot, workspaceService);
        TeamBlueprintRecord team = teamBlueprintService.createTeamBlueprint(
            "customer-service",
            "after-sales",
            "售后团队",
            "处理售后",
            "售后场景",
            "after-sales-ticket",
            List.of(new AgentBlueprintRecord()
                .setAgentId("classifier")
                .setDisplayName("分类员")
                .setResponsibility("处理退款分类")
                .setKnowledgeRefs(List.of("refund", "policy"))
                .setAllowedTools(List.of("order.query"))),
            List.of("prompt://base"),
            "manual",
            Map.of()
        );

        assertTrue(facade.validateTeamBlueprint("customer-service", team).isValid());
        assertTrue(facade.compileTeamBlueprint("customer-service", team).isApplied());

        ScenarioRecord scenario = new ScenarioRecord()
            .setWorkspaceId("customer-service")
            .setScenarioId("after-sales-ticket")
            .setName("售后工单")
            .setDescription("处理退款")
            .setEntryTeamId("after-sales")
            .setSuccessCriteria(List.of("refund policy"));
        var request = facade.buildScenarioIntentRequest(scenario, "refund order");
        assertEquals("after-sales", request.preferredTeamId());
        var plan = facade.planScenarioIntent(scenario, "refund order");
        assertFalse(plan.assignments().isEmpty());

        IntentOrchestrator.IntentRun run = new IntentOrchestrator.IntentRun("facade-run-1", plan.intent(), plan.assignments(), null, "execute", "after-sales", "售后团队");
        run.setStatus(IntentOrchestrator.RunStatus.COMPLETED);
        BusinessRunRecord projection = facade.projectIntentRun("customer-service", "after-sales-ticket", "售后工单", run);
        assertEquals("intent://facade-run-1", projection.getTechnicalTraceRef());
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
