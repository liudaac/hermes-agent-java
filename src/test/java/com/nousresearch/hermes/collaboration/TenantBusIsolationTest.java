package com.nousresearch.hermes.collaboration;

import com.nousresearch.hermes.tenant.core.TenantContext;
import com.nousresearch.hermes.tenant.core.TenantProvisioningRequest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import com.nousresearch.hermes.testutil.IsolatedHermesHome;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Verifies that collaboration buses are isolated per tenant. */
class TenantBusIsolationTest {

    @RegisterExtension
    final IsolatedHermesHome hermesHome = new IsolatedHermesHome();


    @Test
    void tenantContextsGetIsolatedBuses() {
        var a = TenantContext.create("bus-tenant-a-" + System.nanoTime(),
            TenantProvisioningRequest.builder("bus-tenant-a", "test").build());
        var b = TenantContext.create("bus-tenant-b-" + System.nanoTime(),
            TenantProvisioningRequest.builder("bus-tenant-b", "test").build());

        assertNotSame(a.getTenantBus(), b.getTenantBus());
        assertNotEquals(a.getTenantBus().getTenantId(), b.getTenantBus().getTenantId());
    }

    @Test
    void sameAgentIdHandlersDoNotCrossTenants() throws Exception {
        var a = TenantContext.create("bus-cross-a-" + System.nanoTime(),
            TenantProvisioningRequest.builder("bus-cross-a", "test").build());
        var b = TenantContext.create("bus-cross-b-" + System.nanoTime(),
            TenantProvisioningRequest.builder("bus-cross-b", "test").build());
        a.initCollaboration();
        b.initCollaboration();

        a.getTenantBus().register("agent-shared", msg -> reply(a.getTenantBus(), msg, "from-a"));
        b.getTenantBus().register("agent-shared", msg -> reply(b.getTenantBus(), msg, "from-b"));

        var replyA = a.getTenantBus().sendAndWait(AgentMessage.builder("sender-a", "agent-shared", AgentMessage.Type.REQUEST)
            .action("ping").payload(Map.of()).timeoutMs(2_000).build(), 2_000);
        var replyB = b.getTenantBus().sendAndWait(AgentMessage.builder("sender-b", "agent-shared", AgentMessage.Type.REQUEST)
            .action("ping").payload(Map.of()).timeoutMs(2_000).build(), 2_000);

        assertEquals("from-a", replyA.getResultText());
        assertEquals("from-b", replyB.getResultText());
    }

    private static void reply(TenantBus bus, AgentMessage original, String resultText) {
        var reply = AgentMessage.builder(original.getReceiverId(), original.getSenderId(), AgentMessage.Type.RESPONSE)
            .action("done")
            .replyTo(original.getMessageId())
            .payload(Map.of("result", resultText))
            .build();
        reply.setResultText(resultText);
        bus.reply(original, reply);
    }
}
