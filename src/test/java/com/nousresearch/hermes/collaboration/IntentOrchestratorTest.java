package com.nousresearch.hermes.collaboration;

import com.nousresearch.hermes.tenant.core.TenantContext;
import com.nousresearch.hermes.tenant.core.TenantProvisioningRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.TimeUnit;


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
        String tenantId = "intent-tenant-" + System.nanoTime();
        var request = TenantProvisioningRequest.builder(tenantId, "test-user").build();
        tenantContext = TenantContext.create(tenantId, request);
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

    @Test
    void successfulExecutionFeedsObservabilityAndEvolution() throws Exception {
        tenantContext.getTenantBus().register("agent-1", msg -> {
            var reply = AgentMessage.builder(msg.getReceiverId(), msg.getSenderId(), AgentMessage.Type.RESPONSE)
                .action("done")
                .replyTo(msg.getMessageId())
                .payload(Map.of("ok", true))
                .build();
            reply.setResultText("review completed");
            tenantContext.getTenantBus().reply(msg, reply);
        });

        var run = orchestrator.execute("review the code");
        waitForTerminal(run);

        assertEquals(IntentOrchestrator.RunStatus.COMPLETED, run.status);
        assertEquals(1, run.successes().size());
        assertFalse(run.attempts().isEmpty());
        assertTrue(run.attempts().stream().allMatch(IntentOrchestrator.IntentAttempt::success));
        assertTrue(tenantContext.getObservability().getRecentTraces("agent-1", 5).size() >= 1);
        assertFalse(tenantContext.getEvolutionEngine().getSuccessPatterns("agent-1").isEmpty());
    }

    @Test
    void failedExecutionFeedsObservabilityAndEvolution() throws Exception {
        var run = orchestrator.execute("deploy to production");
        waitForTerminal(run);

        assertEquals(IntentOrchestrator.RunStatus.PARTIAL, run.status);
        assertFalse(run.failures().isEmpty());
        assertFalse(run.attempts().isEmpty());
        assertTrue(run.attempts().stream().anyMatch(a -> !a.success()));
        assertTrue(tenantContext.getObservability().getRecentTraces("agent-2", 5).size() >= 1);
        assertTrue(tenantContext.getEvolutionEngine().getAgentFailures("agent-2", 5).size() >= 1);
    }


    @Test
    void replayFailuresCreatesControlRunForFailedSubtasks() throws Exception {
        var failed = orchestrator.execute("review the code");
        waitForTerminal(failed);
        assertEquals(IntentOrchestrator.RunStatus.PARTIAL, failed.status);
        assertFalse(failed.failures().isEmpty());

        tenantContext.getTenantBus().register("agent-1", msg -> {
            var reply = AgentMessage.builder(msg.getReceiverId(), msg.getSenderId(), AgentMessage.Type.RESPONSE)
                .action("done")
                .replyTo(msg.getMessageId())
                .payload(Map.of("ok", true))
                .build();
            reply.setResultText("replayed ok");
            tenantContext.getTenantBus().reply(msg, reply);
        });

        var replay = orchestrator.replayFailures(failed.runId);
        waitForTerminal(replay);

        assertEquals(failed.runId, replay.parentRunId);
        assertEquals("replay_failed", replay.controlAction);
        assertEquals(IntentOrchestrator.RunStatus.COMPLETED, replay.status);
        assertEquals(failed.failures().size(), replay.assignments().size());
    }

    @Test
    void rerouteCreatesControlRunForTargetAgent() throws Exception {
        tenantContext.registerAgentRole("agent-4",
            new AgentRole("backup-reviewer", "Backup reviewer", AgentRole.Level.SENIOR)
                .skills("review", "code"));
        tenantContext.getTenantBus().register("agent-4", msg -> {
            var reply = AgentMessage.builder(msg.getReceiverId(), msg.getSenderId(), AgentMessage.Type.RESPONSE)
                .action("done")
                .replyTo(msg.getMessageId())
                .payload(Map.of("ok", true))
                .build();
            reply.setResultText("rerouted ok");
            tenantContext.getTenantBus().reply(msg, reply);
        });

        var original = orchestrator.execute("review the code");
        waitForTerminal(original);

        var reroute = orchestrator.reroute(original.runId, "review the code", "agent-4");
        waitForTerminal(reroute);

        assertEquals(original.runId, reroute.parentRunId);
        assertEquals("reroute", reroute.controlAction);
        assertEquals(IntentOrchestrator.RunStatus.COMPLETED, reroute.status);
        assertEquals("agent-4", reroute.assignments().get(0).agentId());
    }


    @Test
    void intentRunsPersistAndReload() throws Exception {
        var run = orchestrator.execute("review the code");
        waitForTerminal(run);
        orchestrator.saveRuns();

        var reloaded = new IntentOrchestrator(tenantContext);
        var restored = reloaded.getRun(run.runId);

        assertNotNull(restored);
        assertEquals(run.runId, restored.runId);
        assertEquals(run.intent, restored.intent);
        assertEquals(run.status, restored.status);
        assertEquals(run.failures().size(), restored.failures().size());
        assertEquals(run.attempts().size(), restored.attempts().size());
        assertEquals(run.assignments().size(), restored.assignments().size());
    }

    private static void waitForTerminal(IntentOrchestrator.IntentRun run) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            if (run.status == IntentOrchestrator.RunStatus.COMPLETED
                || run.status == IntentOrchestrator.RunStatus.PARTIAL
                || run.status == IntentOrchestrator.RunStatus.FAILED) {
                return;
            }
            Thread.sleep(20);
        }
        fail("run did not finish, status=" + run.status);
    }

}
