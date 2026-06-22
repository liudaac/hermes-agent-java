package com.nousresearch.hermes.business;

import com.nousresearch.hermes.approval.ToolRisk;
import com.nousresearch.hermes.blueprint.AgentBlueprintRecord;
import com.nousresearch.hermes.blueprint.FoundationCapabilityValidator;
import com.nousresearch.hermes.blueprint.TeamBlueprintCompileResult;
import com.nousresearch.hermes.blueprint.TeamBlueprintCompiler;
import com.nousresearch.hermes.blueprint.TeamBlueprintRecord;
import com.nousresearch.hermes.blueprint.TeamBlueprintService;
import com.nousresearch.hermes.business.approval.BusinessApprovalRecord;
import com.nousresearch.hermes.business.run.BusinessRunProjectionAdapter;
import com.nousresearch.hermes.business.run.BusinessRunRecord;
import com.nousresearch.hermes.collaboration.ScenarioOrchestrator;
import com.nousresearch.hermes.evolution.EvolutionProposalAdapter;
import com.nousresearch.hermes.evolution.EvolutionProposalRecord;
import com.nousresearch.hermes.org.evolution.FailureCase;
import com.nousresearch.hermes.prompt.PromptAssetResolver;
import com.nousresearch.hermes.prompt.PromptAssetService;
import com.nousresearch.hermes.prompt.PromptContext;
import com.nousresearch.hermes.scenario.ScenarioIntentAdapter;
import com.nousresearch.hermes.scenario.ScenarioRecord;
import com.nousresearch.hermes.tenant.core.TenantContext;
import com.nousresearch.hermes.tenant.core.TenantManager;
import com.nousresearch.hermes.tenant.core.TenantManagerConfig;
import com.nousresearch.hermes.tools.ToolEntry;
import com.nousresearch.hermes.tools.ToolRegistry;
import com.nousresearch.hermes.workspace.WorkspaceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BusinessPortalAdapterChainSmokeTest {
    @TempDir
    Path tempDir;

    @Test
    void closesAdapterFirstLoopWithoutProductApiOrUi() {
        Path businessRoot = tempDir.resolve("business/workspaces");
        TenantManager tenantManager = new TenantManager(tempDir.resolve("tenants"), new TenantManagerConfig());
        WorkspaceService workspaceService = new WorkspaceService(businessRoot, tenantManager);
        workspaceService.createWorkspace("customer-service", "客服业务空间", null, "ops", Map.of());
        TenantContext tenant = tenantManager.getTenant("customer-service");

        PromptAssetService promptAssetService = new PromptAssetService(businessRoot, workspaceService);
        promptAssetService.createPromptAsset(
            "customer-service",
            "after-sales-base",
            "售后基础提示词",
            "售后工单处理基础指令",
            "先判断工单类型，再匹配政策，最后给出业务建议。",
            List.of("after-sales", "refund"),
            Map.of("source", "smoke")
        );
        tenant.getMemoryManager().addMemory("退款问题需要先检查签收时间。", java.util.Set.of("refund"));

        PromptAssetResolver promptResolver = new PromptAssetResolver(promptAssetService, workspaceService, tenantManager);
        PromptContext promptContext = promptResolver.resolve(
            "customer-service",
            List.of("prompt://after-sales-base"),
            "refund after sales ticket",
            PromptAssetResolver.ResolveOptions.withFoundationContext()
        );
        assertTrue(promptContext.render().contains("售后基础提示词"));
        assertTrue(promptContext.getSegments().stream().anyMatch(s -> "foundation-memory".equals(s.source())));

        ToolRegistry registry = isolatedRegistry();
        registry.register(new ToolEntry.Builder()
            .name("order.query")
            .toolset("business")
            .schema(Map.of("description", "Query order"))
            .handler(args -> "{}")
            .risk(ToolRisk.LOW)
            .build());

        TeamBlueprintService teamBlueprintService = new TeamBlueprintService(businessRoot, workspaceService);
        TeamBlueprintRecord teamBlueprint = teamBlueprintService.createTeamBlueprint(
            "customer-service",
            "after-sales",
            "售后团队",
            "处理售后退款工单",
            "after-sales-ticket",
            "after-sales-ticket",
            List.of(new AgentBlueprintRecord()
                .setAgentId("policy-expert")
                .setDisplayName("政策专家")
                .setResponsibility("识别退款工单并匹配售后政策")
                .setKnowledgeRefs(List.of("refund", "policy", "after-sales"))
                .setAllowedTools(List.of("order.query"))),
            List.of("prompt://after-sales-base"),
            "先查订单，再判断退款政策。",
            Map.of("source", "smoke")
        );

        FoundationCapabilityValidator validator = new FoundationCapabilityValidator(workspaceService, tenantManager, registry, promptResolver);
        assertTrue(validator.validateTeamBlueprint("customer-service", teamBlueprint).isValid());

        TeamBlueprintCompiler compiler = new TeamBlueprintCompiler(workspaceService, tenantManager, validator);
        TeamBlueprintCompileResult compile = compiler.compileActiveVersion("customer-service", teamBlueprint);
        assertTrue(compile.isApplied());
        assertNotNull(tenant.getTeamManager().getTeam("after-sales"));
        assertNotNull(tenant.getAgentRole("policy-expert"));

        ScenarioRecord scenario = new ScenarioRecord()
            .setWorkspaceId("customer-service")
            .setScenarioId("after-sales-ticket")
            .setName("售后工单处理")
            .setDescription("自动分析售后退款工单并生成建议")
            .setEntryTeamId("after-sales")
            .setSuccessCriteria(List.of("正确识别退款类型", "高风险退款人工审批"))
            .setApprovalRules(List.of("金额超过阈值必须人工审批"))
            .setMetadata(Map.of("contextSignals", List.of("refund", "multi_step")));

        ScenarioIntentAdapter scenarioAdapter = new ScenarioIntentAdapter(workspaceService, tenantManager);
        ScenarioOrchestrator.IntentPlan plan = scenarioAdapter.plan(scenario, "refund order policy");
        assertEquals("after-sales", plan.preferredTeamId());
        assertTrue(plan.assignments().stream().anyMatch(a -> "policy-expert".equals(a.agentId())));

        ScenarioOrchestrator.IntentRun foundationRun = new ScenarioOrchestrator.IntentRun(
            "smoke-run-1",
            plan.intent(),
            plan.assignments(),
            null,
            "execute",
            plan.preferredTeamId(),
            plan.preferredTeamName()
        );
        ScenarioOrchestrator.SubtaskAssignment assignment = plan.assignments().get(0);
        foundationRun.recordSuccess(assignment.subtask(), "identified refund policy path");
        foundationRun.recordAttempt(new ScenarioOrchestrator.IntentAttempt(
            assignment.subtask(),
            assignment.agentId(),
            assignment.roleName(),
            assignment.score(),
            false,
            null,
            "trace-smoke-1",
            true,
            null,
            42,
            System.currentTimeMillis(),
            assignment.teamId(),
            assignment.teamName()
        ));
        foundationRun.setStatus(ScenarioOrchestrator.RunStatus.COMPLETED);

        BusinessRunRecord businessRun = new BusinessRunProjectionAdapter().fromIntentRun(
            "customer-service",
            "after-sales-ticket",
            "售后工单处理",
            foundationRun
        );
        assertEquals("COMPLETED", businessRun.getStatus());
        assertEquals("intent://smoke-run-1", businessRun.getTechnicalTraceRef());
        assertEquals("foundation:intent-run", businessRun.getMetadata().get("source"));

        EvolutionProposalRecord proposal = new EvolutionProposalRecord()
            .setWorkspaceId("customer-service")
            .setProposalId("evp-smoke-1")
            .setScenarioId("after-sales-ticket")
            .setTeamId("after-sales")
            .setSourceInsightId("smoke-insight")
            .setTitle("补强售后政策上下文")
            .setFinding("运行复盘显示售后政策上下文需要更明确")
            .setProposedChange("补充签收时间与退款阈值检查步骤")
            .setExpectedBenefit("降低售后误判率")
            .setEvidence(Map.of("technicalTraceRef", businessRun.getTechnicalTraceRef(), "businessRunId", businessRun.getRunId()))
            .setMetadata(Map.of("rootCause", "INSUFFICIENT_CONTEXT", "severity", "MEDIUM"))
            .setCreatedAt(Instant.now());

        EvolutionProposalAdapter evolutionAdapter = new EvolutionProposalAdapter(workspaceService, tenantManager);
        FailureCase failure = evolutionAdapter.recordFailureLearning(proposal);
        assertEquals("evp-smoke-1", failure.getId());
        assertTrue(tenant.getEvolutionEngine().getTotalFailures() >= 1);

        BusinessApprovalRecord approvalCard = evolutionAdapter.toBusinessApprovalCard(proposal);
        assertEquals("foundation:evolution-proposal-approval", approvalCard.getMetadata().get("source"));
        assertEquals("evp-smoke-1", approvalCard.getMetadata().get("proposalId"));

        var delegatedTask = evolutionAdapter.createDelegatedReviewTask(proposal);
        assertEquals("evolution-proposal:evp-smoke-1", delegatedTask.envelope().runId());
        assertTrue(tenant.getDelegatedTaskStore().list().stream().anyMatch(task -> "evolution-proposal:evp-smoke-1".equals(task.envelope().runId())));
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
