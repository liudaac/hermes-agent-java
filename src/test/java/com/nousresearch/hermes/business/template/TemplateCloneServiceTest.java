package com.nousresearch.hermes.business.template;

import com.nousresearch.hermes.blueprint.TeamBlueprintRecord;
import com.nousresearch.hermes.blueprint.TeamBlueprintService;
import com.nousresearch.hermes.prompt.PromptAssetService;
import com.nousresearch.hermes.scenario.ScenarioRecord;
import com.nousresearch.hermes.scenario.ScenarioService;
import com.nousresearch.hermes.tenant.core.TenantManager;
import com.nousresearch.hermes.tenant.core.TenantManagerConfig;
import com.nousresearch.hermes.workspace.WorkspaceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TemplateCloneServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void clonesHrOnboardingTemplateIntoWorkspace() {
        Path workspaces = tempDir.resolve("business/workspaces");
        TenantManager tenantManager = new TenantManager(tempDir.resolve("tenants"), new TenantManagerConfig());
        WorkspaceService workspaceService = new WorkspaceService(workspaces, tenantManager);
        PromptAssetService promptAssetService = new PromptAssetService(workspaces, workspaceService);
        TeamBlueprintService teamBlueprintService = new TeamBlueprintService(workspaces, workspaceService);
        ScenarioService scenarioService = new ScenarioService(workspaces, workspaceService, teamBlueprintService);

        BusinessTemplateService templateService = new BusinessTemplateService();
        TemplateCloneService cloneService = new TemplateCloneService(
            templateService, workspaceService, promptAssetService, teamBlueprintService, scenarioService);

        CloneRequest req = new CloneRequest();
        req.workspaceName = "测试工作空间";
        TemplateCloneService.CloneResult result = cloneService.clone("hr-onboarding-7day", req);

        assertNotNull(result.workspaceId);
        assertNotNull(result.teamId);
        assertNotNull(result.scenarioId);
        assertTrue(result.workspaceCreated);
        assertFalse(result.promptAssetIds.isEmpty());

        // Verify all artifacts persisted
        assertTrue(workspaceService.getWorkspace(result.workspaceId).isPresent());
        TeamBlueprintRecord team = teamBlueprintService.requireTeamBlueprint(result.workspaceId, result.teamId);
        assertNotNull(team.getActiveVersion());
        assertFalse(team.getVersions().getFirst().getAgents().isEmpty(),
            "Cloned team should contain agents");
        assertFalse(team.getVersions().getFirst().getPromptAssetRefs().isEmpty(),
            "Cloned team should reference prompt assets");
        ScenarioRecord scenario = scenarioService.requireScenario(result.workspaceId, result.scenarioId);
        assertEquals(result.teamId, scenario.getEntryTeamId());
    }

    @Test
    void clonesFinanceTemplateIntoExistingWorkspace() {
        Path workspaces = tempDir.resolve("business/workspaces");
        TenantManager tenantManager = new TenantManager(tempDir.resolve("tenants"), new TenantManagerConfig());
        WorkspaceService workspaceService = new WorkspaceService(workspaces, tenantManager);
        PromptAssetService promptAssetService = new PromptAssetService(workspaces, workspaceService);
        TeamBlueprintService teamBlueprintService = new TeamBlueprintService(workspaces, workspaceService);
        ScenarioService scenarioService = new ScenarioService(workspaces, workspaceService, teamBlueprintService);
        workspaceService.createWorkspace("finance-demo", "财务空间", null, "ops", java.util.Map.of());

        BusinessTemplateService templateService = new BusinessTemplateService();
        TemplateCloneService cloneService = new TemplateCloneService(
            templateService, workspaceService, promptAssetService, teamBlueprintService, scenarioService);

        CloneRequest req = new CloneRequest();
        req.workspaceId = "finance-demo";
        TemplateCloneService.CloneResult result = cloneService.clone("finance-3way-match", req);

        assertEquals("finance-demo", result.workspaceId);
        assertFalse(result.workspaceCreated);
        assertNotNull(result.teamId);
        assertNotNull(result.scenarioId);
    }
}
