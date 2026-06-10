package com.nousresearch.hermes.collaboration;

import com.nousresearch.hermes.org.evolution.FailureCase;
import com.nousresearch.hermes.org.observe.AgentTrace;
import com.nousresearch.hermes.tenant.core.TenantContext;
import com.nousresearch.hermes.tenant.core.TenantProvisioningRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for organization-aware capability scoring. */
class CapabilityScorerTest {
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

}
