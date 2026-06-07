package com.nousresearch.hermes.org.handoff;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HandoffProtocolTest {

    private HandoffProtocol protocol;

    @BeforeEach
    void setUp() {
        protocol = new HandoffProtocol();
        protocol.start();
    }

    @Test
    void testCreateAndResolveHandoff() {
        HandoffContext ctx = protocol.createHandoff(
            new HandoffContext.Builder("agent-tool", "Deploy to production", "The agent wants to deploy version 2.3.1 to production. All tests passed.")
                .addOption("approve", "Approve Deploy", "Deploy version 2.3.1 now")
                .addOption("reject", "Reject", "Do not deploy")
                .addOption("hold", "Wait", "Hold for further review")
                .targetReviewer("ops-lead")
                .maxWaitSeconds(300)
                .build()
        );

        assertNotNull(ctx.getHandoffId());
        assertEquals(HandoffContext.Status.PENDING, ctx.getStatus());
        assertEquals("agent-tool", ctx.getSourceAgentId());
        assertEquals(3, ctx.getOptions().size());

        // Acknowledge
        protocol.acknowledge(ctx.getHandoffId(), "ops-lead");
        assertEquals(HandoffContext.Status.ACKNOWLEDGED, ctx.getStatus());

        // Resolve
        var resolution = protocol.resolve(ctx.getHandoffId(), "ops-lead", "approve", "Looks good, proceed.");
        assertEquals("approve", resolution.option());
        assertEquals("ops-lead", resolution.reviewer());
        assertEquals(HandoffContext.Status.RESOLVED, ctx.getStatus());
    }

    @Test
    void testGetPendingForReviewer() {
        protocol.createHandoff(
            new HandoffContext.Builder("agent-a", "Task 1", "Details")
                .targetReviewer("alice")
                .build()
        );
        protocol.createHandoff(
            new HandoffContext.Builder("agent-b", "Task 2", "Details")
                .targetReviewer("bob")
                .build()
        );

        List<HandoffContext> forAlice = protocol.getPendingFor("alice");
        assertEquals(1, forAlice.size());
        assertEquals("Task 1", forAlice.get(0).getSummary());

        List<HandoffContext> forBob = protocol.getPendingFor("bob");
        assertEquals(1, forBob.size());
        assertEquals("Task 2", forBob.get(0).getSummary());
    }

    @Test
    void testConvenienceApproval() {
        HandoffContext ctx = protocol.requestApproval(
            "ci-bot", "Release v1.0.0", "All checks passed, ready to release.",
            "release-manager", 600
        );

        assertEquals(HandoffContext.Priority.NORMAL, ctx.getPriority());
        assertFalse(ctx.getOptions().isEmpty());
        assertTrue(ctx.getOptions().stream().anyMatch(o -> o.id().equals("approve")));
        assertTrue(ctx.getOptions().stream().anyMatch(o -> o.id().equals("reject")));
        assertTrue(ctx.getOptions().stream().anyMatch(o -> o.id().equals("modify")));
    }

    @Test
    void testPriorityOrdering() {
        HandoffContext low = protocol.createHandoff(
            new HandoffContext.Builder("agent", "Low priority", "details")
                .priority(HandoffContext.Priority.LOW).build()
        );
        HandoffContext critical = protocol.createHandoff(
            new HandoffContext.Builder("agent", "Critical", "details")
                .priority(HandoffContext.Priority.CRITICAL).build()
        );

        List<HandoffContext> pending = protocol.getAllPending();
        assertFalse(pending.isEmpty());
        // Critical should come before LOW
        int criticalIdx = -1, lowIdx = -1;
        for (int i = 0; i < pending.size(); i++) {
            if (pending.get(i).getHandoffId().equals(critical.getHandoffId())) criticalIdx = i;
            if (pending.get(i).getHandoffId().equals(low.getHandoffId())) lowIdx = i;
        }
        assertTrue(criticalIdx < lowIdx, "Critical should be before LOW");
    }

    @Test
    void testSummary() {
        protocol.createHandoff(new HandoffContext.Builder("a", "T1", "D").build());
        protocol.createHandoff(new HandoffContext.Builder("a", "T2", "D").build());

        var summary = protocol.getSummary();
        assertTrue((int) summary.get("pending") >= 2);
    }

    @Test
    void testHandoffContextContainsActionsTaken() {
        HandoffContext ctx = protocol.createHandoff(
            new HandoffContext.Builder("agent", "Error deploying", "Failed to connect to DB")
                .addActionTaken("execute_command", "kubectl get pods", "3 pods running")
                .addActionTaken("web_search", "PostgreSQL connection refused", "Found 3 solutions")
                .build()
        );

        assertEquals(2, ctx.getActionsTaken().size());
        assertEquals("execute_command", ctx.getActionsTaken().get(0).tool());
    }
}
