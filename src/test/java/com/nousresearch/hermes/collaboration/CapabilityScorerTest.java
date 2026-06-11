package com.nousresearch.hermes.collaboration;

import com.nousresearch.hermes.org.evolution.FailureCase;
import com.nousresearch.hermes.org.observe.AgentTrace;
import com.nousresearch.hermes.tenant.core.TenantContext;
import com.nousresearch.hermes.tenant.core.TenantProvisioningRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import com.nousresearch.hermes.testutil.IsolatedHermesHome;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Tests for organization-aware capability scoring. */
class CapabilityScorerTest {

    @RegisterExtension
    final IsolatedHermesHome hermesHome = new IsolatedHermesHome();

    private TenantContext tenant;

    @BeforeEach
    void setUp() {
        var req = TenantProvisioningRequest.builder("score-tenant-" + System.nanoTime(), "test-user").build();
        tenant = TenantContext.create(req.getTenantId(), req);
        tenant.initCollaboration();
    }

    @Test
    void skillAndRoleMatchContributeToScore() {
        var role = new AgentRole("release-manager", "Ships releases", AgentRole.Level.LEAD)
            .skills("deployment", "production", "ci-cd");
        var score = CapabilityScorer.score("deploy production", "agent-release", role, null);
        assertTrue(score.total() > 0);
        assertTrue(score.components().get("skill_match") > 0);
        assertEquals("release-manager", score.roleName());
    }

    @Test
    void onlineAgentGetsAvailabilityBonus() {
        var role = new AgentRole("qa-engineer", "Runs tests", AgentRole.Level.MID)
            .skills("tests", "qa");
        tenant.getTenantBus().register("agent-online", msg -> {});

        var online = CapabilityScorer.score("run tests", "agent-online", role, tenant);
        var offline = CapabilityScorer.score("run tests", "agent-offline", role, tenant);

        assertTrue(online.total() > offline.total(), "online agent should score higher");
        assertTrue(online.components().get("availability") > offline.components().get("availability"));
    }

    @Test
    void highErrorRatePenalizesReliability() {
        var role = new AgentRole("code-reviewer", "Reviews code", AgentRole.Level.SENIOR)
            .skills("code", "review");

        // Build a failed trace for agent-flaky.
        var trace = tenant.getObservability().startTrace("agent-flaky", "s1", "review code");
        trace.step(AgentTrace.Step.error("review failed"));
        trace.end(AgentTrace.Status.FAILED);
        tenant.getObservability().completeTrace(trace);

        var flaky = CapabilityScorer.score("review code", "agent-flaky", role, tenant);
        var fresh = CapabilityScorer.score("review code", "agent-fresh", role, tenant);

        assertTrue(flaky.components().get("reliability") < fresh.components().get("reliability"));
    }

    @Test
    void failureHistoryPenalizesEvolutionScore() {
        var role = new AgentRole("release-manager", "Deploys", AgentRole.Level.LEAD)
            .skills("deploy", "release");
        tenant.getEvolutionEngine().recordFailure(new FailureCase.Builder(
                "agent-release", "deploy", "missing context")
            .rootCause(FailureCase.RootCause.INSUFFICIENT_CONTEXT)
            .severity(FailureCase.Severity.MEDIUM)
            .lesson("query SOP first")
            .build());

        var score = CapabilityScorer.score("deploy release", "agent-release", role, tenant);
        assertTrue(score.components().get("evolution") < 0);
    }

    @Test
    void intentOrchestratorUsesTenantAwareScoring() {
        tenant.registerAgentRole("agent-offline",
            new AgentRole("qa-engineer", "Runs tests", AgentRole.Level.MID).skills("tests", "qa"));
        tenant.registerAgentRole("agent-online",
            new AgentRole("qa-engineer", "Runs tests", AgentRole.Level.MID).skills("tests", "qa"));
        tenant.getTenantBus().register("agent-online", msg -> {});

        var assignment = IntentOrchestrator.findBestMatch(
            "run tests", tenant.listAgentRoles(), tenant);
        assertEquals("agent-online", assignment.agentId());
    }
    @Test
    void assignmentExposesScoreComponentsForExplanation() {
        tenant.registerAgentRole("agent-online",
            new AgentRole("qa-engineer", "Runs tests", AgentRole.Level.MID).skills("tests", "qa"));
        tenant.getTenantBus().register("agent-online", msg -> {});

        var assignment = IntentOrchestrator.findBestMatch(
            "run tests", tenant.listAgentRoles(), tenant);

        assertEquals("agent-online", assignment.agentId());
        assertFalse(assignment.scoreComponents().isEmpty());
        assertTrue(assignment.toMap().containsKey("score_components"));
        assertTrue(((java.util.Map<?, ?>) assignment.toMap().get("score_components")).containsKey("availability"));
    }

    @Test
    void manualOverrideCanDisableOrDeprioritizeAgent() {
        var normal = new AgentRole("qa-engineer", "Runs tests", AgentRole.Level.MID).skills("tests", "qa");
        var disabled = new AgentRole("qa-engineer", "Runs tests", AgentRole.Level.MID).skills("tests", "qa").disabled(true);
        var deprioritized = new AgentRole("qa-engineer", "Runs tests", AgentRole.Level.MID).skills("tests", "qa").deprioritize(1.5);

        var normalScore = CapabilityScorer.score("run tests", "agent-normal", normal, tenant);
        var disabledScore = CapabilityScorer.score("run tests", "agent-disabled", disabled, tenant);
        var deprioritizedScore = CapabilityScorer.score("run tests", "agent-low", deprioritized, tenant);

        assertTrue(disabledScore.total() < deprioritizedScore.total());
        assertTrue(deprioritizedScore.total() < normalScore.total());
        assertTrue(disabledScore.components().get("manual_override") <= -10.0);
        assertEquals(-1.5, deprioritizedScore.components().get("manual_override"), 0.001);
    }

    @Test
    void expiredManualOverrideAutoRestoresBeforeScoring() {
        var role = new AgentRole("qa-engineer", "Runs tests", AgentRole.Level.MID)
            .skills("tests", "qa")
            .disabled(true);
        role.updateMetric("manual_expires_at", System.currentTimeMillis() - 1_000);

        var score = CapabilityScorer.score("run tests", "agent-expired", role, tenant);

        assertEquals(0.0, score.components().get("manual_override"), 0.001);
        assertFalse(role.getMetrics().containsKey("manual_disabled"));
        assertFalse(role.getMetrics().containsKey("manual_penalty"));
        assertFalse(role.getMetrics().containsKey("manual_expires_at"));
    }

    @Test
    void activeTtlManualOverrideStillApplies() {
        var role = new AgentRole("qa-engineer", "Runs tests", AgentRole.Level.MID)
            .skills("tests", "qa")
            .deprioritize(1.5);
        role.updateMetric("manual_expires_at", System.currentTimeMillis() + 60_000);

        var score = CapabilityScorer.score("run tests", "agent-ttl", role, tenant);

        assertEquals(-1.5, score.components().get("manual_override"), 0.001);
        assertTrue(role.getMetrics().containsKey("manual_expires_at"));
    }

    @Test
    void preferredTeamBoostsMemberOverEquallyCapableNonMember() {
        var team = tenant.getTeamManager().createTeam("release", "Release Team", "Ship releases", "test");
        team.addMember("agent-team");

        var teamRole = new AgentRole("qa-engineer", "Runs tests", AgentRole.Level.MID).skills("tests", "qa");
        var otherRole = new AgentRole("qa-engineer", "Runs tests", AgentRole.Level.MID).skills("tests", "qa");

        var teamScore = CapabilityScorer.score("run tests", "agent-team", teamRole, tenant, "release");
        var otherScore = CapabilityScorer.score("run tests", "agent-other", otherRole, tenant, "release");

        assertTrue(teamScore.total() > otherScore.total(), "preferred team member should receive a scheduling boost");
        assertTrue(teamScore.components().get("team_preference") > 0);
        assertEquals(0.0, otherScore.components().get("team_preference"), 0.001);
    }

    @Test
    void noPreferredTeamLeavesEquallyCapableScoresUnchanged() {
        var team = tenant.getTeamManager().createTeam("release", "Release Team", "Ship releases", "test");
        team.addMember("agent-team");

        var teamRole = new AgentRole("qa-engineer", "Runs tests", AgentRole.Level.MID).skills("tests", "qa");
        var otherRole = new AgentRole("qa-engineer", "Runs tests", AgentRole.Level.MID).skills("tests", "qa");

        var teamScore = CapabilityScorer.score("run tests", "agent-team", teamRole, tenant);
        var otherScore = CapabilityScorer.score("run tests", "agent-other", otherRole, tenant);

        assertEquals(otherScore.total(), teamScore.total(), 0.001);
        assertEquals(0.0, teamScore.components().get("team_preference"), 0.001);
        assertEquals(0.0, otherScore.components().get("team_preference"), 0.001);
    }

}
