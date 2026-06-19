package com.nousresearch.hermes.business.run;

import com.nousresearch.hermes.blueprint.AgentBlueprintRecord;
import com.nousresearch.hermes.blueprint.TeamBlueprintService;
import com.nousresearch.hermes.scenario.ScenarioService;
import com.nousresearch.hermes.tenant.core.TenantManager;
import com.nousresearch.hermes.tenant.core.TenantManagerConfig;
import com.nousresearch.hermes.workspace.WorkspaceService;
import com.nousresearch.hermes.scenario.ScenarioIntentAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the run event bus and real-time streaming capability.
 */
class RunEventBusTest {

    @TempDir
    Path tempDir;

    @Test
    void eventBusDeliversEventsToSubscriber() {
        RunEventBus bus = new RunEventBus();
        List<RunEventBus.RunEvent> received = new ArrayList<>();

        bus.subscribe("run-123", event -> received.add(event));

        bus.publish(new RunEventBus.RunEvent("run-123", "ws1",
            RunEventBus.EventType.RUN_STARTED, "started", Map.of()));
        bus.publish(new RunEventBus.RunEvent("run-123", "ws1",
            RunEventBus.EventType.STEP_COMPLETED, "step done", Map.of("step", "classify")));
        bus.publish(new RunEventBus.RunEvent("run-123", "ws1",
            RunEventBus.EventType.RUN_COMPLETED, "done", Map.of()));

        assertEquals(3, received.size());
        assertEquals(RunEventBus.EventType.RUN_STARTED, received.get(0).type());
        assertEquals(RunEventBus.EventType.RUN_COMPLETED, received.get(2).type());
    }

    @Test
    void eventBusDoesNotDeliverToOtherRuns() {
        RunEventBus bus = new RunEventBus();
        List<RunEventBus.RunEvent> received = new ArrayList<>();

        bus.subscribe("run-aaa", event -> received.add(event));

        bus.publish(new RunEventBus.RunEvent("run-bbb", "ws1",
            RunEventBus.EventType.RUN_STARTED, "started", Map.of()));

        assertEquals(0, received.size());
    }

    @Test
    void globalSubscriberReceivesAllEvents() {
        RunEventBus bus = new RunEventBus();
        List<RunEventBus.RunEvent> received = new ArrayList<>();

        bus.subscribeGlobal(event -> received.add(event));

        bus.publish(new RunEventBus.RunEvent("run-1", "ws1",
            RunEventBus.EventType.RUN_STARTED, "s1", Map.of()));
        bus.publish(new RunEventBus.RunEvent("run-2", "ws2",
            RunEventBus.EventType.RUN_STARTED, "s2", Map.of()));

        assertEquals(2, received.size());
    }

    @Test
    void scenarioExecutionPublishesRealTimeEvents() throws Exception {
        TenantManager tenantManager = new TenantManager(tempDir.resolve("tenants"), new TenantManagerConfig());
        WorkspaceService workspaceService = new WorkspaceService(tempDir.resolve("workspaces"), tenantManager);
        workspaceService.createWorkspace("demo", "Demo", null, "ops", Map.of());

        TeamBlueprintService blueprintService = new TeamBlueprintService(tempDir.resolve("workspaces"), workspaceService);
        blueprintService.createTeamBlueprint("demo", "test-team", "Test Team",
            "Test", "test", "test-scenario",
            List.of(
                new AgentBlueprintRecord().setAgentId("worker").setDisplayName("Worker").setResponsibility("Process tasks")
            ),
            List.of(), "Process tasks", Map.of());

        ScenarioService scenarioService = new ScenarioService(tempDir.resolve("workspaces"), workspaceService, blueprintService);
        scenarioService.setScenarioIntentAdapter(new ScenarioIntentAdapter(workspaceService, tenantManager));

        BusinessRunService runService = new BusinessRunService(tempDir.resolve("workspaces"), workspaceService, blueprintService, scenarioService);

        scenarioService.createScenario("demo", "test-scenario", "Test Scenario",
            "Test scenario for streaming", "test-team",
            List.of("Complete task"), List.of(), Map.of());

        // Subscribe to events
        List<RunEventBus.RunEvent> events = new ArrayList<>();
        CountDownLatch completedLatch = new CountDownLatch(1);

        runService.getEventBus().subscribeGlobal(event -> {
            synchronized (events) {
                events.add(event);
            }
            if (event.type().isTerminal()) {
                completedLatch.countDown();
            }
        });

        // Execute scenario
        BusinessRunRecord result = scenarioService.executeScenario(
            "demo", "test-scenario", "process this request", runService, true);

        assertNotNull(result);
        assertNotNull(result.getRunId());

        // Wait for completion (with timeout)
        boolean completed = completedLatch.await(15, TimeUnit.SECONDS);
        assertTrue(completed, "Run should complete within 15 seconds");

        // Verify we got events
        synchronized (events) {
            assertTrue(events.size() >= 2, "Should have at least started + completed events, got: " + events.size());

            boolean hasStarted = events.stream().anyMatch(e -> e.type() == RunEventBus.EventType.RUN_STARTED);
            boolean hasTerminal = events.stream().anyMatch(e -> e.type().isTerminal());

            assertTrue(hasStarted, "Should have RUN_STARTED event");
            assertTrue(hasTerminal, "Should have a terminal event (COMPLETED or FAILED)");
        }

        // Verify final run status
        BusinessRunRecord finalRun = runService.requireRun("demo", result.getRunId());
        assertTrue(
            "COMPLETED".equals(finalRun.getStatus()) || "FAILED".equals(finalRun.getStatus()),
            "Final status should be COMPLETED or FAILED, was: " + finalRun.getStatus()
        );
    }
}
