package com.nousresearch.hermes.soak;

import com.nousresearch.hermes.collaboration.AgentMessage;
import com.nousresearch.hermes.collaboration.AgentRole;
import com.nousresearch.hermes.collaboration.CapabilityScorer;
import com.nousresearch.hermes.collaboration.IntentOrchestrator;
import com.nousresearch.hermes.tenant.audit.AuditEvent;
import com.nousresearch.hermes.tenant.core.TenantContext;
import com.nousresearch.hermes.tenant.core.TenantProvisioningRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Repeatable organization runtime soak harness.
 *
 * <p>Default mode is intentionally small and runs as a CI smoke test. Use
 * {@code -Dhermes.soak=true} to run a larger local soak without changing CI time.</p>
 */
class OrganizationSoakHarnessTest {

    @Test
    void organizationRuntimeSoakHarness() throws Exception {
        boolean soak = Boolean.getBoolean("hermes.soak");
        int tenants = Integer.getInteger("hermes.soak.tenants", soak ? 4 : 2);
        int iterations = Integer.getInteger("hermes.soak.iterations", soak ? 25 : 2);

        List<TenantContext> contexts = new ArrayList<>();
        long prefix = System.nanoTime();

        for (int i = 0; i < tenants; i++) {
            String tenantId = "soak-tenant-" + prefix + "-" + i;
            TenantContext tenant = TenantContext.create(tenantId,
                TenantProvisioningRequest.builder(tenantId, "soak-test").build());
            tenant.initCollaboration();
            tenant.registerAgentRole("agent-reviewer", new AgentRole("reviewer", "Reviews code", AgentRole.Level.SENIOR).skills("review", "code"));
            tenant.registerAgentRole("agent-release", new AgentRole("release-manager", "Ships releases", AgentRole.Level.LEAD).skills("release", "deploy"));
            tenant.getTenantBus().register("agent-reviewer", msg -> reply(tenant, msg, "review ok"));
            tenant.getTenantBus().register("agent-release", msg -> reply(tenant, msg, "release ok"));
            contexts.add(tenant);
        }

        int completed = 0;
        for (TenantContext tenant : contexts) {
            tenant.getAuditLogger().log(AuditEvent.CONTROL_AGENT_OVERRIDE_CHANGED, Map.of(
                "tenantId", tenant.getTenantId(),
                "actor", "soak-harness",
                "reason", "baseline governance event"
            ));

            AgentRole reviewer = tenant.getAgentRole("agent-reviewer");
            reviewer.disabled(true);
            reviewer.updateMetric("manual_expires_at", System.currentTimeMillis() - 1_000);
            var restoredScore = CapabilityScorer.score("review code", "agent-reviewer", reviewer, tenant);
            assertEquals(0.0, restoredScore.components().get("manual_override"), 0.001);

            for (int i = 0; i < iterations; i++) {
                var run = tenant.getIntentOrchestrator().execute("review code and release deploy");
                waitForTerminal(run);
                assertEquals(IntentOrchestrator.RunStatus.COMPLETED, run.status, "run should complete for " + tenant.getTenantId());
                assertTrue(run.successes().size() >= 1);
                assertFalse(run.attempts().isEmpty());
                completed++;
            }

            tenant.getIntentOrchestrator().saveRuns();
            IntentOrchestrator reloaded = new IntentOrchestrator(tenant);
            assertTrue(reloaded.listRuns().size() >= iterations);
            assertNotNull(reloaded.getRun(tenant.getIntentOrchestrator().listRuns().get(0).runId));
        }

        assertEquals(tenants * iterations, completed);
    }

    private static void reply(TenantContext tenant, AgentMessage original, String resultText) {
        var reply = AgentMessage.builder(original.getReceiverId(), original.getSenderId(), AgentMessage.Type.RESPONSE)
            .action("done")
            .replyTo(original.getMessageId())
            .payload(Map.of("result", resultText))
            .build();
        reply.setResultText(resultText);
        tenant.getTenantBus().reply(original, reply);
    }

    private static void waitForTerminal(IntentOrchestrator.IntentRun run) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (run.status == IntentOrchestrator.RunStatus.COMPLETED
                || run.status == IntentOrchestrator.RunStatus.PARTIAL
                || run.status == IntentOrchestrator.RunStatus.FAILED) return;
            Thread.sleep(20);
        }
        fail("run did not finish: " + run.runId + " status=" + run.status);
    }
}
