package com.nousresearch.hermes.business.run;

import com.nousresearch.hermes.blueprint.TeamBlueprintService;
import com.nousresearch.hermes.scenario.ScenarioService;
import com.nousresearch.hermes.tenant.core.TenantManager;
import com.nousresearch.hermes.tenant.core.TenantManagerConfig;
import com.nousresearch.hermes.workspace.WorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Business-focused tests for BusinessRunService: create -> list -> update
 * status -> add step -> recover orphaned runs.
 *
 * Uses TempDir-backed repositories (no real ~/.hermes needed).
 */
class BusinessRunServiceTest {

    @TempDir
    Path tempDir;

    private BusinessRunService runService;
    private WorkspaceService workspaceService;

    @BeforeEach
    void setUp() {
        TenantManager tenantManager = new TenantManager(tempDir.resolve("tenants"), new TenantManagerConfig());
        workspaceService = new WorkspaceService(tempDir.resolve("business/workspaces"), tenantManager);
        TeamBlueprintService teamService = new TeamBlueprintService(workspaceService);
        ScenarioService scenarioService = new ScenarioService(workspaceService, teamService);
        runService = new BusinessRunService(
            tempDir.resolve("business/workspaces"),
            workspaceService,
            teamService,
            scenarioService
        );
        // Create a workspace to run against
        workspaceService.createWorkspace("test-ws", "Test", null, "tester", Map.of());
    }

    @Test
    void createRunPersistsAndLists() {
        BusinessRunRecord run = runService.createRun(
            "test-ws", null, null, null,
            "Process refund", "refund #123", null, null,
            null, null, null,
            BusinessRunService.RUNNING, null,
            List.of(), 0L, 0.0, Map.of(), Map.of()
        );

        assertNotNull(run.getRunId());
        assertEquals("test-ws", run.getWorkspaceId());
        assertEquals(BusinessRunService.RUNNING, run.getStatus());
        assertTrue(run.getCreatedAt() != null);

        // List should find it
        List<BusinessRunRecord> runs = runService.listRuns("test-ws", null, null, 10);
        assertEquals(1, runs.size());
        assertEquals(run.getRunId(), runs.get(0).getRunId());
    }

    @Test
    void updateRunStatusTransitionsAndEmitsEvent() {
        BusinessRunRecord run = createSimpleRun("test-ws");

        BusinessRunRecord updated = runService.updateRunStatus(
            "test-ws", run.getRunId(), BusinessRunService.COMPLETED, "All steps done");

        assertEquals(BusinessRunService.COMPLETED, updated.getStatus());

        // Persisted?
        BusinessRunRecord fetched = runService.requireRun("test-ws", run.getRunId());
        assertEquals(BusinessRunService.COMPLETED, fetched.getStatus());
    }

    @Test
    void addRunStepPersistsAndAppends() {
        BusinessRunRecord run = createSimpleRun("test-ws");

        BusinessRunStep step = new BusinessRunStep()
            .setStepId("step-1")
            .setTitle("Analyze request")
            .setStatus("COMPLETED")
            .setSummary("Analyzed");
        runService.addRunStep("test-ws", run.getRunId(), step);

        BusinessRunRecord fetched = runService.requireRun("test-ws", run.getRunId());
        assertEquals(1, fetched.getSteps().size());
        assertEquals("step-1", fetched.getSteps().get(0).getStepId());
    }

    @Test
    void listRunsFilterByStatus() {
        createSimpleRun("test-ws"); // RUNNING
        BusinessRunRecord r2 = createSimpleRun("test-ws");
        runService.updateRunStatus("test-ws", r2.getRunId(), BusinessRunService.FAILED, "error");

        List<BusinessRunRecord> running = runService.listRuns("test-ws", null, BusinessRunService.RUNNING, 10);
        List<BusinessRunRecord> failed = runService.listRuns("test-ws", null, BusinessRunService.FAILED, 10);

        assertEquals(1, running.size());
        assertEquals(1, failed.size());
    }

    @Test
    void recoverOrphanedRunsMarksRunningAsFailed() {
        // Create 2 RUNNING runs + 1 COMPLETED
        BusinessRunRecord r1 = createSimpleRun("test-ws");
        BusinessRunRecord r2 = createSimpleRun("test-ws");
        BusinessRunRecord r3 = createSimpleRun("test-ws");
        runService.updateRunStatus("test-ws", r3.getRunId(), BusinessRunService.COMPLETED, "done");

        int recovered = runService.recoverOrphanedRuns();

        assertEquals(2, recovered);

        BusinessRunRecord after1 = runService.requireRun("test-ws", r1.getRunId());
        BusinessRunRecord after2 = runService.requireRun("test-ws", r2.getRunId());
        BusinessRunRecord after3 = runService.requireRun("test-ws", r3.getRunId());

        assertEquals(BusinessRunService.FAILED, after1.getStatus());
        assertEquals(BusinessRunService.FAILED, after2.getStatus());
        assertEquals(BusinessRunService.COMPLETED, after3.getStatus()); // untouched
        assertNotNull(after1.getMetadata().get("recoveredFromRestart"));
        assertEquals("RUNNING", after1.getMetadata().get("previousStatus"));
    }

    @Test
    void recoverOrphanedRunsWithNoRunningReturnsZero() {
        BusinessRunRecord r = createSimpleRun("test-ws");
        runService.updateRunStatus("test-ws", r.getRunId(), BusinessRunService.COMPLETED, "done");

        assertEquals(0, runService.recoverOrphanedRuns());
    }

    @Test
    void requireRunThrowsOnMissing() {
        assertThrows(BusinessRunService.BusinessRunNotFoundException.class,
            () -> runService.requireRun("test-ws", "nonexistent-run"));
    }

    // ─── Helper ──────────────────────────────────────────

    private BusinessRunRecord createSimpleRun(String workspaceId) {
        return runService.createRun(
            workspaceId, null, null, null,
            "Task " + System.nanoTime(), "input", null, null,
            null, null, null,
            BusinessRunService.RUNNING, null,
            List.of(), 0L, 0.0, Map.of(), Map.of()
        );
    }
}
