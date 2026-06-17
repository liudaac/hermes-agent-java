package com.nousresearch.hermes.scenario;

import com.nousresearch.hermes.collaboration.AgentRole;
import com.nousresearch.hermes.tenant.core.TenantContext;
import com.nousresearch.hermes.tenant.core.TenantManager;
import com.nousresearch.hermes.tenant.core.TenantManagerConfig;
import com.nousresearch.hermes.workspace.WorkspaceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ScenarioIntentAdapterTest {
    @TempDir
    Path tempDir;

    @Test
    void buildsIntentRequestFromScenarioWithoutPlanning() {
        Fixture fixture = fixture();
        ScenarioRecord scenario = scenario(Map.of("allowDelegation", true, "contextSignals", List.of("production", "multi_step")));

        ScenarioIntentRequest request = fixture.adapter.toIntentRequest(scenario, "请处理退款工单");

        assertEquals("customer-service", request.workspaceId());
        assertEquals("after-sales-ticket", request.scenarioId());
        assertEquals("after-sales", request.preferredTeamId());
        assertTrue(request.allowDelegation());
        assertTrue(request.intent().contains("Scenario: 售后工单处理"));
        assertTrue(request.intent().contains("User request: 请处理退款工单"));
        assertTrue(request.intent().contains("Success criteria:"));
        assertTrue(request.contextSignals().contains("production"));
        assertTrue(request.contextSignals().contains("high_stakes"));
    }

    @Test
    void plansThroughIntentOrchestratorWithPreferredTeam() {
        Fixture fixture = fixture();
        TenantContext tenant = fixture.tenantManager.getTenant("customer-service");
        tenant.registerAgentRole("classifier", new AgentRole("工单分类员", "处理售后分类", AgentRole.Level.LEAD)
            .skills("售后", "工单", "refund"));
        var team = tenant.getTeamManager().createTeam("after-sales", "售后团队", "处理售后", "test");
        team.addMember("classifier");
        team.setLead("classifier");

        var plan = fixture.adapter.plan(scenario(Map.of()), "refund order");

        assertEquals("after-sales", plan.preferredTeamId());
        assertFalse(plan.assignments().isEmpty());
        assertTrue(plan.assignments().stream().anyMatch(a -> "classifier".equals(a.agentId())));
    }

    @Test
    void executeReturnsFoundationIntentRun() {
        Fixture fixture = fixture();
        TenantContext tenant = fixture.tenantManager.getTenant("customer-service");
        tenant.registerAgentRole("classifier", new AgentRole("工单分类员", "处理售后分类", AgentRole.Level.LEAD)
            .skills("售后", "工单", "refund"));

        var run = fixture.adapter.execute(scenario(Map.of()), "refund order");

        assertNotNull(run.runId);
        assertEquals("execute", run.controlAction);
        assertEquals("after-sales", run.preferredTeamId);
        assertFalse(run.assignments().isEmpty());
    }

    private Fixture fixture() {
        TenantManager tenantManager = new TenantManager(tempDir.resolve("tenants"), new TenantManagerConfig());
        WorkspaceService workspaceService = new WorkspaceService(tempDir.resolve("business/workspaces"), tenantManager);
        workspaceService.createWorkspace("customer-service", "客服业务空间", null, "ops", Map.of());
        ScenarioIntentAdapter adapter = new ScenarioIntentAdapter(workspaceService, tenantManager);
        return new Fixture(tenantManager, adapter);
    }

    private ScenarioRecord scenario(Map<String, Object> metadata) {
        return new ScenarioRecord()
            .setWorkspaceId("customer-service")
            .setScenarioId("after-sales-ticket")
            .setName("售后工单处理")
            .setDescription("自动分析售后工单并生成处理建议")
            .setEntryTeamId("after-sales")
            .setSuccessCriteria(List.of("正确识别工单类型", "高风险退款必须人工审批"))
            .setApprovalRules(List.of("退款超过 1000 元必须人工审批"))
            .setMetadata(metadata);
    }

    private record Fixture(TenantManager tenantManager, ScenarioIntentAdapter adapter) {}
}
