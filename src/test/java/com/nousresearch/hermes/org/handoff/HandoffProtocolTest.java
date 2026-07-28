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

}
