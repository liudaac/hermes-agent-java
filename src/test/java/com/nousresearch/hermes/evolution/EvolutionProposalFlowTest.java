package com.nousresearch.hermes.evolution;

import com.nousresearch.hermes.blueprint.AgentBlueprintRecord;
import com.nousresearch.hermes.blueprint.TeamBlueprintService;
import com.nousresearch.hermes.business.run.BusinessRunRecord;
import com.nousresearch.hermes.business.run.BusinessRunService;
import com.nousresearch.hermes.business.run.BusinessRunStep;
import com.nousresearch.hermes.evalset.EvalSetRecord;
import com.nousresearch.hermes.evalset.EvalSetService;
import com.nousresearch.hermes.scenario.ScenarioIntentAdapter;
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

/**
 * Tests for evolution proposal flow:
 *   generation → approval → apply → activate → evaluation
 */
class EvolutionProposalFlowTest {

    @TempDir
    Path tempDir;

    @Test
    void fullProposalLifecycle() {
        // Setup
        TenantManager tenantManager = new TenantManager(tempDir.resolve("tenants"), new TenantManagerConfig());
        WorkspaceService workspaceService = new WorkspaceService(tempDir.resolve("workspaces"), tenantManager);
        workspaceService.createWorkspace("demo", "Demo", null, "ops", Map.of());

        TeamBlueprintService blueprintService = new TeamBlueprintService(tempDir.resolve("workspaces"), workspaceService);
        blueprintService.createTeamBlueprint("demo", "test-team", "Test Team",
            "Test team", "test", "test-scenario",
            List.of(
                new AgentBlueprintRecord().setAgentId("worker").setDisplayName("Worker").setResponsibility("Process tasks")
            ),
            List.of(), "SOP", Map.of());

        ScenarioService scenarioService = new ScenarioService(tempDir.resolve("workspaces"), workspaceService, blueprintService);
        scenarioService.setScenarioIntentAdapter(new ScenarioIntentAdapter(workspaceService, tenantManager));

        BusinessRunService runService = new BusinessRunService(tempDir.resolve("workspaces"), workspaceService, blueprintService, scenarioService);

        scenarioService.createScenario("demo", "test-scenario", "Test Scenario",
            "Description", "test-team",
            List.of("Complete task"), List.of(), Map.of());

        EvolutionProposalService proposalService = new EvolutionProposalService(
            tempDir.resolve("workspaces"), workspaceService, blueprintService);

        // === Step 1: Create proposal ===
        EvolutionProposalRecord proposal = proposalService.createProposal(
            "demo", null, "test-scenario", "test-team",
            null, "Improve classification accuracy",
            "Classifier is making repeated mistakes on refund requests",
            "Add refund policy knowledge and improve routing rules",
            "Reduce classification errors by 30%",
            Map.of("sampleSize", 10),
            Map.of("source", "test"));

        assertNotNull(proposal.getProposalId());
        assertEquals(EvolutionProposalService.DRAFT, proposal.getStatus());

        // === Step 2: Request approval ===
        proposal = proposalService.requestApproval("demo", proposal.getProposalId(), null);
        assertEquals(EvolutionProposalService.NEEDS_APPROVAL, proposal.getStatus());

        // === Step 3: Approve ===
        proposal = proposalService.approve("demo", proposal.getProposalId(), "test-user", "Looks good");
        assertEquals(EvolutionProposalService.APPROVED, proposal.getStatus());

        // === Step 4: Apply (creates draft version) ===
        proposal = proposalService.apply("demo", proposal.getProposalId(), "test-team");
        assertEquals(EvolutionProposalService.APPLIED, proposal.getStatus());
        assertNotNull(proposal.getTargetDraftVersion());
        assertTrue(proposal.getTargetDraftVersion() > 1, "Should create a new draft version");

        // Verify draft version exists
        var blueprint = blueprintService.getTeamBlueprint("demo", "test-team").orElseThrow();
        assertEquals(1, blueprint.getActiveVersion(), "Active version should still be v1");
        assertTrue(blueprint.getVersions().size() >= 2, "Should have at least 2 versions");

        // === Step 5: Activate (rolls out draft version) ===
        proposal = proposalService.activate("demo", proposal.getProposalId());
        assertEquals(EvolutionProposalService.APPLIED, proposal.getStatus());

        // Verify active version changed
        blueprint = blueprintService.getTeamBlueprint("demo", "test-team").orElseThrow();
        assertEquals(proposal.getTargetDraftVersion(), blueprint.getActiveVersion(),
            "Active version should be the draft version after activation");
    }

    @Test
    void proposalGeneratorCreatesFromFailedRuns() {
        TenantManager tenantManager = new TenantManager(tempDir.resolve("tenants"), new TenantManagerConfig());
        WorkspaceService workspaceService = new WorkspaceService(tempDir.resolve("workspaces"), tenantManager);
        workspaceService.createWorkspace("demo", "Demo", null, "ops", Map.of());

        TeamBlueprintService blueprintService = new TeamBlueprintService(tempDir.resolve("workspaces"), workspaceService);
        blueprintService.createTeamBlueprint("demo", "test-team", "Test Team",
            "Test", "test", "test-scenario",
            List.of(
                new AgentBlueprintRecord().setAgentId("worker").setDisplayName("Worker").setResponsibility("Process")
            ),
            List.of(), "SOP", Map.of());

        ScenarioService scenarioService = new ScenarioService(tempDir.resolve("workspaces"), workspaceService, blueprintService);
        scenarioService.setScenarioIntentAdapter(new ScenarioIntentAdapter(workspaceService, tenantManager));

        BusinessRunService runService = new BusinessRunService(tempDir.resolve("workspaces"), workspaceService, blueprintService, scenarioService);

        scenarioService.createScenario("demo", "test-scenario", "Test Scenario",
            "Description", "test-team", List.of(), List.of(), Map.of());

        EvolutionProposalService proposalService = new EvolutionProposalService(
            tempDir.resolve("workspaces"), workspaceService, blueprintService);
        EvolutionProposalGenerator generator = new EvolutionProposalGenerator(proposalService, runService);

        // Create some failed runs with the same pattern
        for (int i = 0; i < 3; i++) {
            BusinessRunRecord run = runService.createRun(
                "demo", "test-team", "refund", "test-scenario",
                "Refund request " + i, "customer wants refund",
                "Could not process", "System error", "escalate", "HIGH",
                "review manually", BusinessRunService.FAILED, null,
                List.of(
                    new BusinessRunStep()
                        .setStepId("step-1")
                        .setTitle("refund-processing")
                        .setSummary("Failed to process refund")
                        .setActor("worker")
                        .setStatus("FAILED")
                ),
                0, 0.0, Map.of(), Map.of()
            );
            assertNotNull(run.getRunId());
        }

        // Generate proposals from recent runs
        List<EvolutionProposalRecord> proposals = generator.generateFromRecentRuns("demo", "test-team", 20);

        assertFalse(proposals.isEmpty(), "Should generate at least one proposal for repeated failures");
        assertTrue(proposals.get(0).getTitle().toLowerCase().contains("refund"),
            "Proposal should reference the failure pattern");
        assertEquals(EvolutionProposalService.DRAFT, proposals.get(0).getStatus());
    }

    @Test
    void evalSetRunsAgainstActiveVersion() {
        TenantManager tenantManager = new TenantManager(tempDir.resolve("tenants"), new TenantManagerConfig());
        WorkspaceService workspaceService = new WorkspaceService(tempDir.resolve("workspaces"), tenantManager);
        workspaceService.createWorkspace("demo", "Demo", null, "ops", Map.of());

        TeamBlueprintService blueprintService = new TeamBlueprintService(tempDir.resolve("workspaces"), workspaceService);
        blueprintService.createTeamBlueprint("demo", "eval-team", "Eval Team",
            "Eval", "eval", "eval-scenario",
            List.of(
                new AgentBlueprintRecord().setAgentId("worker").setDisplayName("Worker").setResponsibility("Evaluate")
            ),
            List.of(), "SOP", Map.of());

        ScenarioService scenarioService = new ScenarioService(tempDir.resolve("workspaces"), workspaceService, blueprintService);
        scenarioService.setScenarioIntentAdapter(new ScenarioIntentAdapter(workspaceService, tenantManager));

        BusinessRunService runService = new BusinessRunService(tempDir.resolve("workspaces"), workspaceService, blueprintService, scenarioService);

        scenarioService.createScenario("demo", "eval-scenario", "Eval Scenario",
            "For testing", "eval-team", List.of(), List.of(), Map.of());

        EvalSetService evalSetService = new EvalSetService(tempDir.resolve("workspaces"), workspaceService, scenarioService);

        // Create eval set with cases
        List<EvalSetRecord.EvalCase> cases = List.of(
            new EvalSetRecord.EvalCase()
                .setCaseId("case-1")
                .setName("Simple request")
                .setInput("Hello, can you help me?")
                .setExpectedOutput("help"),
            new EvalSetRecord.EvalCase()
                .setCaseId("case-2")
                .setName("Another request")
                .setInput("Tell me about the weather")
                .setExpectedOutput("weather")
        );

        EvalSetRecord evalSet = evalSetService.createEvalSet(
            "demo", "eval-scenario", "test-eval", "Test Eval Set", "For testing", cases, Map.of());

        assertEquals(2, evalSet.getCases().size());

        // Run evaluation
        EvalSetService.EvalResult result = evalSetService.runEvaluation(
            "demo", "eval-scenario", "test-eval", runService);

        assertNotNull(result.evalRunId());
        assertEquals(2, result.total());
        assertTrue(result.passed() >= 0 && result.passed() <= 2);
        assertTrue(result.failed() >= 0 && result.failed() <= 2);
        assertEquals(2, result.cases().size(), "Should have result for each case");

        // Each case result should have a status
        for (var caseResult : result.cases()) {
            assertNotNull(caseResult.status());
            assertTrue(
                "PASSED".equals(caseResult.status()) ||
                "FAILED".equals(caseResult.status()) ||
                "ERROR".equals(caseResult.status()),
                "Case status should be PASSED, FAILED, or ERROR, was: " + caseResult.status()
            );
        }
    }
}
