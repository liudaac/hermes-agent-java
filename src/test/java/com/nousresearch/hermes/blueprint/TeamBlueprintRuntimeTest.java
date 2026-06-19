package com.nousresearch.hermes.blueprint;

import com.nousresearch.hermes.collaboration.IntentOrchestrator;
import com.nousresearch.hermes.scenario.ScenarioService;
import com.nousresearch.hermes.business.run.BusinessRunRecord;
import com.nousresearch.hermes.business.run.BusinessRunService;
import com.nousresearch.hermes.tenant.core.TenantManager;
import com.nousresearch.hermes.tenant.core.TenantManagerConfig;
import com.nousresearch.hermes.workspace.WorkspaceService;
import com.nousresearch.hermes.scenario.ScenarioIntentAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end test for Team Blueprint Runtime.
 *
 * Verifies the full chain:
 *   blueprint → agent instantiation → bus registration → orchestrator delegation → real execution
 */
class TeamBlueprintRuntimeTest {

    @TempDir
    Path tempDir;

    @Test
    void ensureTeamRuntimeCreatesAgentInstancesOnBus() {
        TenantManager tenantManager = new TenantManager(tempDir.resolve("tenants"), new TenantManagerConfig());
        WorkspaceService workspaceService = new WorkspaceService(tempDir.resolve("workspaces"), tenantManager);
        workspaceService.createWorkspace("demo-workspace", "Demo Workspace", null, "ops", Map.of());

        TeamBlueprintService blueprintService = new TeamBlueprintService(tempDir.resolve("workspaces"), workspaceService);
        TeamBlueprintRuntime runtime = new TeamBlueprintRuntime(workspaceService, blueprintService);

        // Create a team blueprint with two agents
        blueprintService.createTeamBlueprint("demo-workspace", "support-team", "Support Team",
            "Customer support team", "support", "support-ticket",
            List.of(
                new AgentBlueprintRecord().setAgentId("classifier").setDisplayName("Ticket Classifier").setResponsibility("Classify incoming tickets"),
                new AgentBlueprintRecord().setAgentId("responder").setDisplayName("Response Writer").setResponsibility("Write customer responses")
            ),
            List.of(), "Classify and respond to tickets", Map.of());

        // Ensure runtime — this should spin up both agents
        runtime.ensureTeamRuntime("demo-workspace", "support-team");

        assertTrue(runtime.isTeamActive("demo-workspace", "support-team"),
            "Team should be active after ensureTeamRuntime");

        // Both agents should be registered on the tenant bus
        var tenantCtx = workspaceService.resolveTenantContext("demo-workspace");
        assertNotNull(tenantCtx, "Tenant context should exist");
        assertTrue(tenantCtx.getTenantBus().isRegistered("classifier"),
            "classifier agent should be registered on bus");
        assertTrue(tenantCtx.getTenantBus().isRegistered("responder"),
            "responder agent should be registered on bus");
    }

    @Test
    void ensureTeamRuntimeIsIdempotent() {
        TenantManager tenantManager = new TenantManager(tempDir.resolve("tenants"), new TenantManagerConfig());
        WorkspaceService workspaceService = new WorkspaceService(tempDir.resolve("workspaces"), tenantManager);
        workspaceService.createWorkspace("demo-workspace", "Demo Workspace", null, "ops", Map.of());

        TeamBlueprintService blueprintService = new TeamBlueprintService(tempDir.resolve("workspaces"), workspaceService);
        TeamBlueprintRuntime runtime = new TeamBlueprintRuntime(workspaceService, blueprintService);

        blueprintService.createTeamBlueprint("demo-workspace", "support-team", "Support Team",
            "Description", "support", "support-ticket",
            List.of(
                new AgentBlueprintRecord().setAgentId("classifier").setDisplayName("Ticket Classifier").setResponsibility("Classify tickets")
            ),
            List.of(), "SOP", Map.of());

        // Call ensure twice — should not fail or create duplicates
        runtime.ensureTeamRuntime("demo-workspace", "support-team");
        runtime.ensureTeamRuntime("demo-workspace", "support-team");

        assertTrue(runtime.isTeamActive("demo-workspace", "support-team"));
    }

    @Test
    void orchestratorCanDelegateToBlueprintAgents() {
        TenantManager tenantManager = new TenantManager(tempDir.resolve("tenants"), new TenantManagerConfig());
        WorkspaceService workspaceService = new WorkspaceService(tempDir.resolve("workspaces"), tenantManager);
        workspaceService.createWorkspace("demo-workspace", "Demo Workspace", null, "ops", Map.of());

        TeamBlueprintService blueprintService = new TeamBlueprintService(tempDir.resolve("workspaces"), workspaceService);
        TeamBlueprintRuntime runtime = new TeamBlueprintRuntime(workspaceService, blueprintService);

        blueprintService.createTeamBlueprint("demo-workspace", "support-team", "Support Team",
            "Customer support", "support", "support-ticket",
            List.of(
                new AgentBlueprintRecord().setAgentId("classifier").setDisplayName("Ticket Classifier")
                    .setResponsibility("Classify incoming support tickets by category")
            ),
            List.of(), "Classify and respond", Map.of());

        runtime.ensureTeamRuntime("demo-workspace", "support-team");

        // Get the orchestrator from the tenant context and execute
        var tenantCtx = workspaceService.resolveTenantContext("demo-workspace");
        IntentOrchestrator orchestrator = tenantCtx.getIntentOrchestrator();

        IntentOrchestrator.IntentRun run = orchestrator.execute(
            "Classify this support ticket: customer wants a refund",
            "support-team");

        assertNotNull(run, "IntentRun should be created");
        assertNotNull(run.runId, "Run ID should be set");

        // Wait briefly for async execution
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // The run should have completed or be progressing
        assertTrue(
            run.status == IntentOrchestrator.RunStatus.COMPLETED ||
            run.status == IntentOrchestrator.RunStatus.PARTIAL ||
            run.status == IntentOrchestrator.RunStatus.RUNNING,
            "Run should be in a terminal or running state, was: " + run.status
        );
    }

    @Test
    void scenarioExecuteProducesBusinessRunWithRealAgents() {
        TenantManager tenantManager = new TenantManager(tempDir.resolve("tenants"), new TenantManagerConfig());
        WorkspaceService workspaceService = new WorkspaceService(tempDir.resolve("workspaces"), tenantManager);
        workspaceService.createWorkspace("demo-workspace", "Demo Workspace", null, "ops", Map.of());

        TeamBlueprintService blueprintService = new TeamBlueprintService(tempDir.resolve("workspaces"), workspaceService);
        ScenarioService scenarioService = new ScenarioService(tempDir.resolve("workspaces"), workspaceService, blueprintService);
        scenarioService.setScenarioIntentAdapter(new ScenarioIntentAdapter(workspaceService, tenantManager));
        BusinessRunService runService = new BusinessRunService(tempDir.resolve("workspaces"), workspaceService, blueprintService, scenarioService);

        // Create team + scenario
        blueprintService.createTeamBlueprint("demo-workspace", "support-team", "Support Team",
            "Customer support team", "support", "support-ticket",
            List.of(
                new AgentBlueprintRecord().setAgentId("classifier").setDisplayName("Ticket Classifier")
                    .setResponsibility("Classify incoming support tickets")
            ),
            List.of(), "Classify tickets", Map.of());

        scenarioService.createScenario("demo-workspace", "support-ticket", "Support Ticket Handling",
            "Handle customer support tickets", "support-team",
            List.of("Correctly classify tickets"),
            List.of("High value refunds need approval"), Map.of());

        // Execute scenario
        BusinessRunRecord result = scenarioService.executeScenario(
            "demo-workspace", "support-ticket",
            "Customer says their order arrived damaged and wants a refund",
            runService, true);

        assertNotNull(result, "BusinessRunRecord should be returned");
        assertNotNull(result.getRunId(), "Run ID should be set");
        assertEquals("demo-workspace", result.getWorkspaceId());
        assertEquals("support-ticket", result.getScenarioId());
        assertNotNull(result.getSteps(), "Run should have steps");
        assertFalse(result.getSteps().isEmpty(), "Run should have at least one step");
        assertTrue(result.getSteps().size() >= 1,
            "Run should have steps from the execution projection");
    }
}
