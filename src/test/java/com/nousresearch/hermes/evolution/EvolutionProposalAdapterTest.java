package com.nousresearch.hermes.evolution;

import com.nousresearch.hermes.approval.ApprovalRequest;
import com.nousresearch.hermes.business.approval.BusinessApprovalRecord;
import com.nousresearch.hermes.org.evolution.FailureCase;
import com.nousresearch.hermes.tenant.core.TenantContext;
import com.nousresearch.hermes.tenant.core.TenantManager;
import com.nousresearch.hermes.tenant.core.TenantManagerConfig;
import com.nousresearch.hermes.workspace.WorkspaceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EvolutionProposalAdapterTest {
    @TempDir
    Path tempDir;

    @Test
    void convertsProposalToFailureCaseAndRecordsLearning() {
        Fixture fixture = fixture();
        EvolutionProposalRecord proposal = proposal(Map.of("rootCause", "INSUFFICIENT_CONTEXT", "severity", "HIGH"));

        FailureCase failure = fixture.adapter.toFailureCase(proposal);
        assertEquals("evp-1", failure.getId());
        assertEquals("after-sales", failure.getAgentId());
        assertEquals(FailureCase.RootCause.INSUFFICIENT_CONTEXT, failure.getRootCause());
        assertEquals(FailureCase.Severity.HIGH, failure.getSeverity());
        assertTrue(failure.getLesson().contains("补充政策边界"));

        FailureCase recorded = fixture.adapter.recordFailureLearning(proposal);
        assertEquals("evp-1", recorded.getId());
        assertEquals(1, fixture.tenant.getEvolutionEngine().getTotalFailures());
    }

    @Test
    void projectsProposalApprovalCardWithoutApprovingFoundationRequest() {
        Fixture fixture = fixture();
        EvolutionProposalRecord proposal = proposal(Map.of("risk", "HIGH"));

        ApprovalRequest request = fixture.adapter.toApprovalRequest(proposal);
        BusinessApprovalRecord card = fixture.adapter.toBusinessApprovalCard(proposal);

        assertEquals("evolution-proposal:evp-1", request.getOperation());
        assertTrue(request.isDangerous(), "high-risk proposal should be dangerous approval request");
        assertEquals("foundation:evolution-proposal-approval", card.getMetadata().get("source"));
        assertEquals("evp-1", card.getMetadata().get("proposalId"));
        assertEquals("HIGH", card.getRiskLevel());
        assertTrue(card.getEvidence().get("proposedChange").toString().contains("补充政策边界"));
    }

    @Test
    void createsAdvisoryDelegatedReviewTask() {
        Fixture fixture = fixture();
        EvolutionProposalRecord proposal = proposal(Map.of());

        var envelope = fixture.adapter.toDelegatedTaskEnvelope(proposal);
        assertEquals("evolution-proposal:evp-1", envelope.runId());
        assertEquals("after-sales", envelope.suggestedTeamId());
        assertEquals("evolution-review", envelope.suggestedProfile());

        var task = fixture.adapter.createDelegatedReviewTask(proposal);
        assertNotNull(task.taskId());
        assertTrue(fixture.tenant.getDelegatedTaskStore().list().stream()
            .anyMatch(stored -> "evolution-proposal:evp-1".equals(stored.envelope().runId())));
        assertEquals("evolution-proposal:evp-1", task.envelope().runId());
    }

    private Fixture fixture() {
        TenantManager tenantManager = new TenantManager(tempDir.resolve("tenants"), new TenantManagerConfig());
        WorkspaceService workspaceService = new WorkspaceService(tempDir.resolve("business/workspaces"), tenantManager);
        workspaceService.createWorkspace("customer-service", "客服业务空间", null, "ops", Map.of());
        TenantContext tenant = tenantManager.getTenant("customer-service");
        EvolutionProposalAdapter adapter = new EvolutionProposalAdapter(workspaceService, tenantManager);
        return new Fixture(tenant, adapter);
    }

    private EvolutionProposalRecord proposal(Map<String, Object> metadata) {
        return new EvolutionProposalRecord()
            .setWorkspaceId("customer-service")
            .setProposalId("evp-1")
            .setScenarioId("after-sales-ticket")
            .setTeamId("after-sales")
            .setSourceInsightId("insight-1")
            .setTitle("优化售后政策识别")
            .setFinding("失败集中在上下文不足导致政策边界识别错误")
            .setProposedChange("补充政策边界检查步骤，审批高风险退款")
            .setExpectedBenefit("降低售后误判率")
            .setEvidence(Map.of("failedRuns", 3))
            .setMetadata(metadata)
            .setCreatedAt(Instant.now());
    }

    private record Fixture(TenantContext tenant, EvolutionProposalAdapter adapter) {}
}
