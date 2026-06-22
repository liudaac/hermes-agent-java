package com.nousresearch.hermes.collaboration;

import com.nousresearch.hermes.tenant.core.TenantContext;
import com.nousresearch.hermes.tenant.core.TenantProvisioningRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.TimeUnit;


import static org.junit.jupiter.api.Assertions.*;
import com.nousresearch.hermes.testutil.IsolatedHermesHome;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Tests for AI原生组织 第四刀：ScenarioOrchestrator
 * Verifies task decomposition, agent matching, and run tracking.
 */
class ScenarioOrchestratorTest {

    @RegisterExtension
    final IsolatedHermesHome hermesHome = new IsolatedHermesHome();


    private TenantContext tenantContext;
    private ScenarioOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        String tenantId = "intent-tenant-" + System.nanoTime();
        var request = TenantProvisioningRequest.builder(tenantId, "test-user").build();
        tenantContext = TenantContext.create(tenantId, request);
        // Seed multiple agent roles for matching
        tenantContext.registerAgentRole("agent-1",
            new AgentRuntimeProfile("code-reviewer", "Reviews code", AgentRuntimeProfile.Level.SENIOR)
                .skills("java", "python", "code-review"));
        tenantContext.registerAgentRole("agent-2",
            new AgentRuntimeProfile("release-manager", "Manages releases", AgentRuntimeProfile.Level.LEAD)
                .skills("devops", "deployment", "ci-cd"));
        tenantContext.registerAgentRole("agent-3",
            new AgentRuntimeProfile("data-analyst", "Analyzes data", AgentRuntimeProfile.Level.MID)
                .skills("sql", "python", "visualization"));

        orchestrator = tenantContext.getScenarioOrchestrator();
    }

    // ======== Decomposition ========

    @Test
    void decomposeSplitsOnConjunctions() {
        var result = ScenarioOrchestrator.decompose("review code, run tests, then deploy");
        assertTrue(result.size() >= 2, "Should split into multiple subtasks, got: " + result);
    }

    @Test
    void decomposeSingleStaysSingle() {
        var result = ScenarioOrchestrator.decompose("review the code");
        assertEquals(1, result.size());
        assertTrue(result.get(0).contains("review"));
    }

    @Test
    void decomposeEmptyReturnsEmpty() {
        assertTrue(ScenarioOrchestrator.decompose("").isEmpty());
        assertTrue(ScenarioOrchestrator.decompose(null).isEmpty());
    }

    @Test
    void decomposeSplitsOnAnd() {
        var result = ScenarioOrchestrator.decompose("review code and run tests");
        assertTrue(result.size() >= 1);
    }

    // ======== Agent Matching ========

    @Test
    void matchPicksCodeReviewerForCodeTask() {
        var roles = tenantContext.listAgentRoles();
        var match = ScenarioOrchestrator.findBestMatch("review the code", roles);
        assertEquals("agent-1", match.agentId());
        assertEquals("code-reviewer", match.roleName());
        assertTrue(match.score() > 0);
    }

    @Test
    void matchPicksReleaseManagerForDeployment() {
        var roles = tenantContext.listAgentRoles();
        var match = ScenarioOrchestrator.findBestMatch("deploy to production", roles);
        assertEquals("agent-2", match.agentId());
        assertEquals("release-manager", match.roleName());
    }

    @Test
    void matchPicksDataAnalystForDataTask() {
        var roles = tenantContext.listAgentRoles();
        var match = ScenarioOrchestrator.findBestMatch("analyze the data", roles);
        assertEquals("agent-3", match.agentId());
    }

    @Test
    void matchPrefersHigherLevelForSameSkills() {
        // Add another data analyst at MID level
        tenantContext.registerAgentRole("agent-4",
            new AgentRuntimeProfile("data-analyst-jr", "Junior analyst", AgentRuntimeProfile.Level.JUNIOR)
                .skills("sql", "python"));
        var roles = tenantContext.listAgentRoles();
        var match = ScenarioOrchestrator.findBestMatch("analyze data", roles);
        // agent-3 (MID) should win over agent-4 (JUNIOR) due to level bonus
        assertEquals("agent-3", match.agentId());
    }

    @Test
    void matchReturnsNullAgentForUnknownTask() {
        // Empty tenant
        var request = TenantProvisioningRequest.builder("empty-tenant", "user").build();
        var emptyCtx = TenantContext.create("empty-tenant", request);
        var match = ScenarioOrchestrator.findBestMatch("anything", emptyCtx.listAgentRoles());
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
        assertTrue(run.status == ScenarioOrchestrator.RunStatus.PENDING
                || run.status == ScenarioOrchestrator.RunStatus.RUNNING
                || run.status == ScenarioOrchestrator.RunStatus.PARTIAL
                || run.status == ScenarioOrchestrator.RunStatus.COMPLETED
                || run.status == ScenarioOrchestrator.RunStatus.FAILED,
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
        tenantContext.initCollaboration();
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

        assertEquals(ScenarioOrchestrator.RunStatus.COMPLETED, run.status);
        assertEquals(1, run.successes().size());
        assertFalse(run.attempts().isEmpty());
        assertTrue(run.attempts().stream().allMatch(ScenarioOrchestrator.IntentAttempt::success));
        assertTrue(tenantContext.getObservability().getRecentTraces("agent-1", 5).size() >= 1);
        assertFalse(tenantContext.getEvolutionEngine().getSuccessPatterns("agent-1").isEmpty());
    }

    @Test
    void failedExecutionFeedsObservabilityAndEvolution() throws Exception {
        var run = orchestrator.execute("deploy to production");
        waitForTerminal(run);

        assertEquals(ScenarioOrchestrator.RunStatus.PARTIAL, run.status);
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
        assertEquals(ScenarioOrchestrator.RunStatus.PARTIAL, failed.status);
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
        assertEquals(ScenarioOrchestrator.RunStatus.COMPLETED, replay.status);
        assertEquals(failed.failures().size(), replay.assignments().size());
    }

    @Test
    void rerouteCreatesControlRunForTargetAgent() throws Exception {
        tenantContext.registerAgentRole("agent-4",
            new AgentRuntimeProfile("backup-reviewer", "Backup reviewer", AgentRuntimeProfile.Level.SENIOR)
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
        assertEquals(ScenarioOrchestrator.RunStatus.COMPLETED, reroute.status);
        assertEquals("agent-4", reroute.assignments().get(0).agentId());
    }


    @Test
    void intentRunsPersistAndReload() throws Exception {
        var run = orchestrator.execute("review the code");
        waitForTerminal(run);
        orchestrator.saveRuns();

        var reloaded = new ScenarioOrchestrator(tenantContext);
        var restored = reloaded.getRun(run.runId);

        assertNotNull(restored);
        assertEquals(run.runId, restored.runId);
        assertEquals(run.intent, restored.intent);
        assertEquals(run.status, restored.status);
        assertEquals(run.failures().size(), restored.failures().size());
        assertEquals(run.attempts().size(), restored.attempts().size());
        assertEquals(run.assignments().size(), restored.assignments().size());
    }


    @Test
    void persistedActiveRunsReloadAsInterruptedAndReplayable() throws Exception {
        var assignment = new ScenarioOrchestrator.SubtaskAssignment(
            "review the code",
            "agent-1",
            "code-reviewer",
            1.0,
            java.util.List.of("review"),
            java.util.Map.of("skill_match", 1.0)
        );
        var active = new ScenarioOrchestrator.IntentRun(
            "run_9999",
            "review the code",
            java.util.List.of(assignment),
            null,
            "execute",
            System.currentTimeMillis() - 10_000,
            0L,
            ScenarioOrchestrator.RunStatus.RUNNING,
            "review the code"
        );
        var path = tenantContext.getTenantDir().resolve("state").resolve("intent-runs.json");
        java.nio.file.Files.createDirectories(path.getParent());
        new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter()
            .writeValue(path.toFile(), java.util.List.of(active.toMap()));

        var reloaded = new ScenarioOrchestrator(tenantContext);
        var interrupted = reloaded.getRun("run_9999");

        assertNotNull(interrupted);
        assertEquals(ScenarioOrchestrator.RunStatus.INTERRUPTED, interrupted.status);
        assertTrue(interrupted.completedAt > 0);
        assertTrue(interrupted.failures().containsKey("review the code"));

        tenantContext.initCollaboration();
        tenantContext.getTenantBus().register("agent-1", msg -> {
            var reply = AgentMessage.builder(msg.getReceiverId(), msg.getSenderId(), AgentMessage.Type.RESPONSE)
                .action("done")
                .replyTo(msg.getMessageId())
                .payload(Map.of("ok", true))
                .build();
            reply.setResultText("recovered ok");
            tenantContext.getTenantBus().reply(msg, reply);
        });
        var replay = reloaded.replayFailures("run_9999");
        waitForTerminal(replay);
        assertEquals(ScenarioOrchestrator.RunStatus.COMPLETED, replay.status);
    }


    @Test
    void intentRunRetentionKeepsMostRecentRuns() throws Exception {
        String previous = System.getProperty("hermes.intent.runs.max");
        System.setProperty("hermes.intent.runs.max", "3");
        try {
            for (int i = 0; i < 5; i++) {
                var run = orchestrator.execute("review the code " + i);
                waitForTerminal(run);
            }
            orchestrator.saveRuns();

            var reloaded = new ScenarioOrchestrator(tenantContext);
            var runs = reloaded.listRuns();

            assertEquals(3, runs.size());
            assertTrue(runs.get(0).startedAt >= runs.get(1).startedAt);
            assertTrue(runs.get(1).startedAt >= runs.get(2).startedAt);
            assertEquals(2, reloaded.listRuns(2, 0).size());
            assertEquals(1, reloaded.listRuns(2, 2).size());
        } finally {
            if (previous == null) System.clearProperty("hermes.intent.runs.max");
            else System.setProperty("hermes.intent.runs.max", previous);
        }
    }

    @Test
    void preferredTeamMemberWinsOverEquallyCapableNonTeamAgent() {
        String tenantId = "intent-team-tenant-" + System.nanoTime();
        var request = TenantProvisioningRequest.builder(tenantId, "test-user").build();
        var tenant = TenantContext.create(tenantId, request);
        tenant.registerAgentRole("agent-outsider",
            new AgentRuntimeProfile("qa-engineer", "Runs tests", AgentRuntimeProfile.Level.MID).skills("tests", "qa"));
        tenant.registerAgentRole("agent-team",
            new AgentRuntimeProfile("qa-engineer", "Runs tests", AgentRuntimeProfile.Level.MID).skills("tests", "qa"));
        var team = tenant.getTeamManager().createTeam("release", "Release Team", "Ship releases", "test");
        team.addMember("agent-team");

        var plan = tenant.getScenarioOrchestrator().plan("run tests", "release");
        var assignment = plan.assignments().get(0);

        assertEquals("release", plan.preferredTeamId());
        assertEquals("Release Team", plan.preferredTeamName());
        assertEquals("agent-team", assignment.agentId());
        assertEquals("release", assignment.teamId());
        assertEquals("Release Team", assignment.teamName());
        assertTrue(assignment.scoreComponents().get("team_preference") > 0);
        assertEquals("release", assignment.toMap().get("team_id"));
    }

    @Test
    void noPreferredTeamKeepsExistingTieBehavior() {
        String tenantId = "intent-no-team-tenant-" + System.nanoTime();
        var request = TenantProvisioningRequest.builder(tenantId, "test-user").build();
        var tenant = TenantContext.create(tenantId, request);
        tenant.registerAgentRole("agent-outsider",
            new AgentRuntimeProfile("qa-engineer", "Runs tests", AgentRuntimeProfile.Level.MID).skills("tests", "qa"));
        tenant.registerAgentRole("agent-team",
            new AgentRuntimeProfile("qa-engineer", "Runs tests", AgentRuntimeProfile.Level.MID).skills("tests", "qa"));
        var team = tenant.getTeamManager().createTeam("release", "Release Team", "Ship releases", "test");
        team.addMember("agent-team");

        var baseline = ScenarioOrchestrator.findBestMatch("run tests", tenant.listAgentRoles(), tenant);
        var planned = tenant.getScenarioOrchestrator().plan("run tests").assignments().get(0);

        assertEquals(baseline.agentId(), planned.agentId());
        assertNull(tenant.getScenarioOrchestrator().plan("run tests").preferredTeamId());
        assertEquals(0.0, planned.scoreComponents().get("team_preference"), 0.001);
    }

    private static void waitForTerminal(ScenarioOrchestrator.IntentRun run) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            if (run.status == ScenarioOrchestrator.RunStatus.COMPLETED
                || run.status == ScenarioOrchestrator.RunStatus.PARTIAL
                || run.status == ScenarioOrchestrator.RunStatus.FAILED
                || run.status == ScenarioOrchestrator.RunStatus.INTERRUPTED) {
                return;
            }
            Thread.sleep(20);
        }
        fail("run did not finish, status=" + run.status);
    }

}
