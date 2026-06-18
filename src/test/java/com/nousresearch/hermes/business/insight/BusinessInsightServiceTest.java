package com.nousresearch.hermes.business.insight;

import com.nousresearch.hermes.blueprint.AgentBlueprintRecord;
import com.nousresearch.hermes.blueprint.TeamBlueprintService;
import com.nousresearch.hermes.business.approval.BusinessApprovalService;
import com.nousresearch.hermes.business.run.BusinessRunService;
import com.nousresearch.hermes.scenario.ScenarioService;
import com.nousresearch.hermes.tenant.core.TenantManager;
import com.nousresearch.hermes.tenant.core.TenantManagerConfig;
import com.nousresearch.hermes.workspace.WorkspaceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BusinessInsightServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void suggestsCreatingWorkspaceWhenSystemIsEmpty() {
        Fixture fixture = fixture(false);

        BusinessInsightSummary summary = fixture.insightService.summarize(null);

        assertEquals(0, summary.getWorkspaceCount());
        assertEquals("insight-create-workspace", summary.getInsights().get(0).getInsightId());
        assertEquals("create-workspace", summary.getNextActions().get(0).get("id"));
    }

    @Test
    void suggestsCreatingTeamWhenWorkspaceHasNoTeam() {
        Fixture fixture = fixture(true);

        BusinessInsightSummary summary = fixture.insightService.summarize("customer-service");

        assertEquals(1, summary.getWorkspaceCount());
        assertEquals(0, summary.getTeamCount());
        assertTrue(summary.getInsights().stream().anyMatch(i -> "insight-create-team".equals(i.getInsightId())));
    }

    @Test
    void reportsFailuresAndPendingHighRiskApprovals() {
        Fixture fixture = fixture(true);
        fixture.teamBlueprintService.createTeamBlueprint(
            "customer-service",
            "after-sales",
            "售后工单团队",
            "处理售后工单",
            "售后",
            "after-sales-ticket",
            List.of(new AgentBlueprintRecord().setAgentId("policy").setDisplayName("政策专家")),
            List.of(),
            "处理售后问题",
            Map.of()
        );
        fixture.scenarioService.createScenario("customer-service", "after-sales-ticket", "售后", null, "after-sales", List.of(), List.of(), Map.of());
        fixture.runService.createRun("customer-service", "after-sales", "售后", "after-sales-ticket", "成功任务", null, "完成", null, null, null, null, "COMPLETED", null, List.of(), 0L, 0.0, Map.of(), Map.of());
        fixture.runService.createRun("customer-service", "after-sales", "售后", "after-sales-ticket", "失败任务", null, "失败", "工具失败", null, null, null, "FAILED", null, List.of(), 0L, 0.0, Map.of(), Map.of());
        fixture.approvalService.createApproval("customer-service", "after-sales", "高风险退款", "需要审批", null, null, null, null, "HIGH", Map.of(), Map.of());

        BusinessInsightSummary summary = fixture.insightService.summarize("customer-service");

        assertEquals(1, summary.getTeamCount());
        assertEquals(2, summary.getRunCount());
        assertEquals(1, summary.getFailedRunCount());
        assertEquals(50.0, summary.getFailureRate());
        assertEquals(1, summary.getPendingApprovalCount());
        assertEquals(1, summary.getHighRiskApprovalCount());
        assertTrue(summary.getInsights().stream().anyMatch(i -> "insight-run-failures".equals(i.getInsightId())));
        assertTrue(summary.getInsights().stream().anyMatch(i -> "insight-pending-approvals".equals(i.getInsightId())));
        assertTrue(summary.getNextActions().stream().anyMatch(a -> "review-approvals".equals(a.get("id"))));
        assertTrue(summary.getNextActions().stream().anyMatch(a -> "review-failed-runs".equals(a.get("id"))));
    }

    private Fixture fixture(boolean createWorkspace) {
        TenantManager tenantManager = new TenantManager(tempDir.resolve("tenants"), new TenantManagerConfig());
        WorkspaceService workspaceService = new WorkspaceService(tempDir.resolve("business/workspaces"), tenantManager);
        TeamBlueprintService teamBlueprintService = new TeamBlueprintService(tempDir.resolve("business/workspaces"), workspaceService);
        ScenarioService scenarioService = new ScenarioService(tempDir.resolve("business/workspaces"), workspaceService, teamBlueprintService);
        BusinessRunService runService = new BusinessRunService(tempDir.resolve("business/workspaces"), workspaceService, teamBlueprintService, scenarioService);
        BusinessApprovalService approvalService = new BusinessApprovalService(tempDir.resolve("business/workspaces"), workspaceService);
        if (createWorkspace) {
            workspaceService.createWorkspace("customer-service", "客服业务空间", null, "ops", Map.of());
        }
        return new Fixture(workspaceService, teamBlueprintService, scenarioService, runService, approvalService,
            new BusinessInsightService(workspaceService, teamBlueprintService, runService, approvalService));
    }

    private record Fixture(WorkspaceService workspaceService,
                           TeamBlueprintService teamBlueprintService,
                           ScenarioService scenarioService,
                           BusinessRunService runService,
                           BusinessApprovalService approvalService,
                           BusinessInsightService insightService) {}
}
