package com.nousresearch.hermes.business.run;

import com.nousresearch.hermes.blueprint.TeamBlueprintService;
import com.nousresearch.hermes.scenario.ScenarioService;
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

class BusinessRunServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void createsListsAndLoadsBusinessRunStory() {
        BusinessRunService service = serviceWithWorkspace();

        BusinessRunRecord run = service.createRun(
            "customer-service",
            "after-sales",
            "售后工单处理",
            "after-sales-ticket",
            "用户申请退款",
            "用户表示签收 3 天后想退货",
            "建议同意用户发起退货申请",
            "订单签收 3 天，商品非特殊类目，金额 89 元，符合 7 天无理由初步条件",
            "生成客服回复草稿",
            "无需人工审批",
            "若用户上传破损图片，则转入质量问题流程",
            "COMPLETED",
            "trace://intent-run-1",
            List.of(step("classify", "判断工单类型", "识别为退款问题"), step("policy", "匹配售后政策", "符合 7 天无理由初步条件")),
            Map.of("durationMs", 1234, "cost", 0.01),
            Map.of("source", "test")
        );

        assertTrue(run.getRunId().startsWith("run-"));
        assertEquals("COMPLETED", run.getStatus());
        assertEquals(2, run.getSteps().size());
        assertEquals("step-1", run.getSteps().get(0).getStepId(), "service should normalize missing step ids");
        assertNotNull(run.getSteps().get(0).getTimestamp());
        assertTrue(Files.exists(tempDir.resolve("business/workspaces/customer-service/runs/" + run.getRunId() + ".json")));

        List<BusinessRunRecord> all = service.listRuns("customer-service", null, null, 50);
        assertEquals(1, all.size());
        assertEquals(run.getRunId(), all.get(0).getRunId());
        assertEquals(1, service.listRuns("customer-service", "after-sales", "COMPLETED", 50).size());
        assertEquals(0, service.listRuns("customer-service", "other-team", "COMPLETED", 50).size());
        assertEquals(run.getResultSummary(), service.requireRun("customer-service", run.getRunId()).getResultSummary());
    }

    @Test
    void rejectsRunForMissingWorkspace() {
        TenantManager tenantManager = new TenantManager(tempDir.resolve("tenants"), new TenantManagerConfig());
        WorkspaceService workspaceService = new WorkspaceService(tempDir.resolve("business/workspaces"), tenantManager);
        TeamBlueprintService teamBlueprintService = new TeamBlueprintService(tempDir.resolve("business/workspaces"), workspaceService);
        ScenarioService scenarioService = new ScenarioService(tempDir.resolve("business/workspaces"), workspaceService, teamBlueprintService);
        BusinessRunService service = new BusinessRunService(tempDir.resolve("business/workspaces"), workspaceService, teamBlueprintService, scenarioService);

        assertThrows(WorkspaceService.WorkspaceNotFoundException.class,
            () -> service.createRun("missing", "team", null, null, "任务", null, null, null, null, null, null, null, null, List.of(), 0L, 0.0, Map.of(), Map.of()));
    }

    private BusinessRunService serviceWithWorkspace() {
        TenantManager tenantManager = new TenantManager(tempDir.resolve("tenants"), new TenantManagerConfig());
        WorkspaceService workspaceService = new WorkspaceService(tempDir.resolve("business/workspaces"), tenantManager);
        workspaceService.createWorkspace("customer-service", "客服业务空间", null, "ops", Map.of());
        TeamBlueprintService teamBlueprintService = new TeamBlueprintService(tempDir.resolve("business/workspaces"), workspaceService);
        teamBlueprintService.createTeamBlueprint("customer-service", "after-sales", "售后团队", "处理售后",
            null, null, List.of(), List.of(), null, Map.of());
        ScenarioService scenarioService = new ScenarioService(tempDir.resolve("business/workspaces"), workspaceService, teamBlueprintService);
        scenarioService.createScenario("customer-service", "after-sales-ticket", "售后工单", null, "after-sales", List.of(), List.of(), Map.of());
        return new BusinessRunService(tempDir.resolve("business/workspaces"), workspaceService, teamBlueprintService, scenarioService);
    }

    private BusinessRunStep step(String actor, String title, String summary) {
        return new BusinessRunStep()
            .setActor(actor)
            .setTitle(title)
            .setSummary(summary)
            .setEvidence("业务依据");
    }
}
