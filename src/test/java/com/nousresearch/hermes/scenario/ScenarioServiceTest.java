package com.nousresearch.hermes.scenario;

import com.nousresearch.hermes.blueprint.TeamBlueprintService;
import com.nousresearch.hermes.tenant.core.TenantManager;
import com.nousresearch.hermes.tenant.core.TenantManagerConfig;
import com.nousresearch.hermes.workspace.WorkspaceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ScenarioServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void createsListsAndLoadsScenario() {
        Fixture fixture = fixtureWithWorkspace();

        ScenarioRecord scenario = fixture.scenarioService.createScenario(
            "customer-service",
            "after-sales-ticket",
            "售后工单处理",
            "自动分析售后工单并生成处理建议",
            "after-sales-team",
            List.of("正确识别工单类型", "生成可执行处理建议"),
            List.of("高风险退款必须人工审批"),
            Map.of("source", "test")
        );

        assertEquals("customer-service", scenario.getWorkspaceId());
        assertEquals("after-sales-ticket", scenario.getScenarioId());
        assertEquals("after-sales-team", scenario.getEntryTeamId());
        assertEquals("ACTIVE", scenario.getStatus());
        assertTrue(Files.exists(tempDir.resolve("business/workspaces/customer-service/scenarios/after-sales-ticket.json")));
        assertEquals(1, fixture.scenarioService.listScenarios("customer-service").size());
        assertEquals("售后工单处理", fixture.scenarioService.requireScenario("customer-service", "after-sales-ticket").getName());
    }

    @Test
    void rejectsDuplicateAndMissingWorkspace() {
        Fixture fixture = fixtureWithWorkspace();
        fixture.scenarioService.createScenario("customer-service", "refund", "退款处理", null, null, List.of(), List.of(), Map.of());

        assertThrows(ScenarioService.ScenarioAlreadyExistsException.class,
            () -> fixture.scenarioService.createScenario("customer-service", "refund", "退款处理", null, null, List.of(), List.of(), Map.of()));
        assertThrows(WorkspaceService.WorkspaceNotFoundException.class,
            () -> fixture.scenarioService.createScenario("missing", "refund", "退款处理", null, null, List.of(), List.of(), Map.of()));
    }

    private Fixture fixtureWithWorkspace() {
        TenantManager tenantManager = new TenantManager(tempDir.resolve("tenants"), new TenantManagerConfig());
        WorkspaceService workspaceService = new WorkspaceService(tempDir.resolve("business/workspaces"), tenantManager);
        workspaceService.createWorkspace("customer-service", "客服业务空间", null, "ops", Map.of());
        TeamBlueprintService teamBlueprintService = new TeamBlueprintService(tempDir.resolve("business/workspaces"), workspaceService);
        teamBlueprintService.createTeamBlueprint("customer-service", "after-sales-team", "售后团队", "处理售后",
            null, null, List.of(), List.of(), null, Map.of());
        ScenarioService scenarioService = new ScenarioService(tempDir.resolve("business/workspaces"), workspaceService, teamBlueprintService);
        return new Fixture(workspaceService, scenarioService);
    }

    private record Fixture(WorkspaceService workspaceService, ScenarioService scenarioService) {}
}
