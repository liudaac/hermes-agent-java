package com.nousresearch.hermes.business.run;

import com.nousresearch.hermes.collaboration.IntentOrchestrator;
import com.nousresearch.hermes.org.observe.AgentTrace;
import com.nousresearch.hermes.tenant.core.TenantManager;
import com.nousresearch.hermes.tenant.core.TenantManagerConfig;
import com.nousresearch.hermes.workspace.WorkspaceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BusinessRunProjectionAdapterTest {
    @TempDir
    Path tempDir;

    @Test
    void projectsCompletedIntentRunIntoBusinessRunRecord() {
        var assignment = new IntentOrchestrator.SubtaskAssignment("classify refund", "classifier", "工单分类员", 0.91, List.of("refund"));
        var run = new IntentOrchestrator.IntentRun("run_42", "Scenario: 售后工单处理\nUser request: refund", List.of(assignment), null, "execute", "after-sales", "售后团队");
        run.recordSuccess("classify refund", "identified refund ticket");
        run.recordAttempt(new IntentOrchestrator.IntentAttempt("classify refund", "classifier", "工单分类员", 0.91, false, null, "trace-1", true, null, 123, System.currentTimeMillis(), "after-sales", "售后团队"));
        run.setStatus(IntentOrchestrator.RunStatus.COMPLETED);

        AgentTrace trace = new AgentTrace("trace-1", "classifier", "session-1", "classify refund")
            .step(AgentTrace.Step.decision("Refund ticket", 0.9, List.of("exchange")))
            .end(AgentTrace.Status.SUCCESS);

        BusinessRunRecord record = new BusinessRunProjectionAdapter().fromIntentRun("customer-service", "after-sales-ticket", "售后工单处理", run, List.of(trace));

        assertEquals("business-run_42", record.getRunId());
        assertEquals("customer-service", record.getWorkspaceId());
        assertEquals("after-sales", record.getTeamId());
        assertEquals("COMPLETED", record.getStatus());
        assertEquals("intent://run_42", record.getTechnicalTraceRef());
        assertEquals("foundation:intent-run", record.getMetadata().get("source"));
        assertEquals(1, record.getMetrics().get("succeeded"));
        assertEquals(0, record.getMetrics().get("failed"));
        assertTrue(record.getSteps().stream().anyMatch(step -> "assignment-1".equals(step.getStepId())));
        assertTrue(record.getSteps().stream().anyMatch(step -> "attempt-1".equals(step.getStepId())));
        assertTrue(record.getSteps().stream().anyMatch(step -> "trace-1".equals(step.getStepId())));
    }

    @Test
    void projectsFailedIntentRunAsReviewNeededStory() {
        var assignment = new IntentOrchestrator.SubtaskAssignment("deploy", "release", "发布员", 0.8, List.of("deployment"));
        var run = new IntentOrchestrator.IntentRun("run_43", "deploy", List.of(assignment), null, "execute", "release-team", "发布团队");
        run.recordFailure("deploy", "Agent release not on bus");
        run.recordAttempt(new IntentOrchestrator.IntentAttempt("deploy", "release", "发布员", 0.8, false, null, "trace-2", false, "Agent release not on bus", 50, System.currentTimeMillis(), "release-team", "发布团队"));
        run.setStatus(IntentOrchestrator.RunStatus.PARTIAL);

        BusinessRunRecord record = new BusinessRunProjectionAdapter().fromIntentRun("ops", "release", "发布", run);

        assertEquals("FAILED", record.getStatus());
        assertTrue(record.getResultSummary().contains("partially completed"));
        assertTrue(record.getRiskJudgement().contains("Needs operator review"));
        assertTrue(record.getNextSuggestion().contains("replayFailures"));
        assertTrue(record.getSteps().stream().anyMatch(step -> "FAILED".equals(step.getStatus())));
    }

    @Test
    void canPersistProjectionThroughExistingBusinessRunService() {
        TenantManager tenantManager = new TenantManager(tempDir.resolve("tenants"), new TenantManagerConfig());
        WorkspaceService workspaceService = new WorkspaceService(tempDir.resolve("business/workspaces"), tenantManager);
        workspaceService.createWorkspace("customer-service", "客服业务空间", null, "ops", Map.of());
        BusinessRunService service = new BusinessRunService(tempDir.resolve("business/workspaces"), workspaceService);

        var assignment = new IntentOrchestrator.SubtaskAssignment("classify", "classifier", "工单分类员", 0.9, List.of("refund"));
        var run = new IntentOrchestrator.IntentRun("run_44", "classify", List.of(assignment), null, "execute", "after-sales", "售后团队");
        run.setStatus(IntentOrchestrator.RunStatus.COMPLETED);
        BusinessRunProjectionAdapter adapter = new BusinessRunProjectionAdapter();
        BusinessRunRecord projection = adapter.fromIntentRun("customer-service", "after-sales-ticket", "售后", run);

        BusinessRunRecord persisted = adapter.persistProjection(service, projection);

        assertTrue(persisted.getRunId().startsWith("run-"));
        assertEquals("intent://run_44", persisted.getTechnicalTraceRef());
        assertEquals("business-run_44", persisted.getMetadata().get("projectionRunId"));
        assertEquals(1, service.listRuns("customer-service", "after-sales", "COMPLETED", 20).size());
    }
}
