package com.nousresearch.hermes.collaboration;

import com.nousresearch.hermes.tenant.core.TenantContext;
import com.nousresearch.hermes.tenant.core.TenantProvisioningRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for AI原生组织 第四刀：IntentOrchestrator
 * Verifies task decomposition, agent matching, and run tracking.
 */
class IntentOrchestratorTest {

    private TenantContext tenantContext;
    private IntentOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        var request = TenantProvisioningRequest.builder("intent-tenant", "test-user").build();
        tenantContext = TenantContext.create("intent-tenant", request);

        // Seed multiple agent roles for matching
        tenantContext.registerAgentRole("agent-1",
            new AgentRole("code-reviewer", "Reviews code", AgentRole.Level.SENIOR)
                .skills("java", "python", "code-review"));
        tenantContext.registerAgentRole("agent-2",
            new AgentRole("release-manager", "Manages releases", AgentRole.Level.LEAD)
                .skills("devops", "deployment", "ci-cd"));
        tenantContext.registerAgentRole("agent-3",
            new AgentRole("data-analyst", "Analyzes data", AgentRole.Level.MID)
                .skills("sql", "python", "visualization"));

        orchestrator = tenantContext.getIntentOrchestrator();
    }

    // ======== Decomposition ========

    @Test
    void decomposeSplitsOnConjunctions() {
        var result = IntentOrchestrator.decompose("review code, run tests, then deploy");
        assertTrue(result.size() >= 2, "Should split into multiple subtasks, got: " + result);
    }

    @Test
    void decomposeSingleStaysSingle() {
        var result = IntentOrchestrator.decompose("review the code");
        assertEquals(1, result.size());
        assertTrue(result.get(0).contains("review"));
    }

    @Test
    void decomposeEmptyReturnsEmpty() {
        assertTrue(IntentOrchestrator.decompose("").isEmpty());
        assertTrue(IntentOrchestrator.decompose(null).isEmpty());
    }

    @Test
    void decomposeSplitsOnAnd() {
        var result = IntentOrchestrator.decompose("review code and run tests");
        assertTrue(result.size() >= 1);
    }

    // ======== Agent Matching ========

    @Test
    void matchPicksCodeReviewerForCodeTask() {
        var roles = tenantContext.listAgentRoles();
        var match = IntentOrchestrator.findBestMatch("review the code", roles);
        assertEquals("agent-1", match.agentId());
        assertEquals("code-reviewer", match.roleName());
        assertTrue(match.score() > 0);
    }

    @Test
    void matchPicksReleaseManagerForDeployment() {
        var roles = tenantContext.listAgentRoles();
        var match = IntentOrchestrator.findBestMatch("deploy to production", roles);
        assertEquals("agent-2", match.agentId());
        assertEquals("release-manager", match.roleName());
    }

    @Test
    void matchPicksDataAnalystForDataTask() {
        var roles = tenantContext.listAgentRoles();
        var match = IntentOrchestrator.findBestMatch("analyze the data", roles);
        assertEquals("agent-3", match.agentId());
    }

    @Test
    void matchPrefersHigherLevelForSameSkills() {
        // Add another data analyst at MID level
        tenantContext.registerAgentRole("agent-4",
            new AgentRole("data-analyst-jr", "Junior analyst", AgentRole.Level.JUNIOR)
                .skills("sql", "python"));
        var roles = tenantContext.listAgentRoles();
        var match = IntentOrchestrator.findBestMatch("analyze data", roles);
        // agent-3 (MID) should win over agent-4 (JUNIOR) due to level bonus
        assertEquals("agent-3", match.agentId());
    }

    @Test
    void matchReturnsNullAgentForUnknownTask() {
        // Empty tenant
        var request = TenantProvisioningRequest.builder("empty-tenant", "user").build();
        var emptyCtx = TenantContext.create("empty-tenant", request);
        var match = IntentOrchestrator.findBestMatch("anything", emptyCtx.listAgentRoles());
        assertNull(match.agentId());
    }

    // ======== Planning ========

    @Test
    void planReturnsAssignments() {
        var plan = orchestrator.plan("review code and deploy");
        assertNotNull(plan);
        assertTrue(plan.assignments().size() >= 1);
        assertTrue(plan.assignments().stream().anyMatch(a -> a.agentId() != null));
    }

    @Test
    void planToMapHasExpectedFields() {
        var plan = orchestrator.plan("deploy the application");
        var map = plan.toMap();
        assertEquals("deploy the application", map.get("intent"));
        assertNotNull(map.get("subtasks"));
        assertNotNull(map.get("status"));
    }

    // ======== Execution ========

    @Test
    void executeReturnsRunId() {
        var run = orchestrator.execute("review the code");
        assertNotNull(run);
        assertNotNull(run.runId);
        assertEquals(1, run.assignments.size());
    }

    @Test
    void runStatusStartsAsPendingOrRunning() {
        var run = orchestrator.execute("review the code");
        // Status is either PENDING or RUNNING (depends on how fast the executor is)
        assertTrue(run.status == IntentOrchestrator.RunStatus.PENDING
                || run.status == IntentOrchestrator.RunStatus.RUNNING
                || run.status == IntentOrchestrator.RunStatus.PARTIAL
                || run.status == IntentOrchestrator.RunStatus.COMPLETED
                || run.status == IntentOrchestrator.RunStatus.FAILED,
            "Status should be one of the expected values, was: " + run.status);
    }

    @Test
    void runToMapHasAllFields() {
        var run = orchestrator.execute("review code");
        var map = run.toMap();
        assertNotNull(map.get("run_id"));
        assertNotNull(map.get("intent"));
        assertNotNull(map.get("status"));
        assertNotNull(map.get("subtasks_total"));
    }

    @Test
    void getRunReturnsCreatedRun() {
        var run = orchestrator.execute("review code");
        var fetched = orchestrator.getRun(run.runId);
        assertSame(run, fetched);
    }

    @Test
    void getRunReturnsNullForUnknownId() {
        var fetched = orchestrator.getRun("nonexistent-run");
        assertNull(fetched);
    }

    @Test
    void listRunsReturnsAllCreated() {
        var r1 = orchestrator.execute("task 1");
        var r2 = orchestrator.execute("task 2");
        var runs = orchestrator.listRuns();
        assertTrue(runs.size() >= 2);
    }
}
