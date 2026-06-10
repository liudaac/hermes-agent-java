package com.nousresearch.hermes.collaboration;

import com.nousresearch.hermes.tenant.core.TenantContext;
import com.nousresearch.hermes.tenant.core.TenantManager;
import com.nousresearch.hermes.tenant.core.TenantManagerConfig;
import com.nousresearch.hermes.tenant.core.TenantProvisioningRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies tenant-scoped buses are released on tenant lifecycle boundaries. */
class TenantBusLifecycleTest {

    @TempDir
    Path tempDir;

    @Test
    void destroyingTenantRemovesScopedBusAndHandlers() throws Exception {
        String tenantId = "bus-life-" + System.nanoTime();
        TenantContext tenant = TenantContext.create(tenantId,
            TenantProvisioningRequest.builder(tenantId, "test").build());
        tenant.initCollaboration();
        TenantBus first = tenant.getTenantBus();
        first.register("agent", msg -> reply(first, msg, "old-handler"));

        assertEquals("old-handler", first.sendAndWait(messageTo("agent"), 2_000).getResultText());

        tenant.destroy(true);

        TenantBus second = TenantBus.forTenant(tenantId);
        second.start();
        assertNotSame(first, second);
        assertFalse(second.isRegistered("agent"));
        assertThrows(TenantBus.TimeoutException.class,
            () -> second.sendAndWait(messageTo("agent"), 50));
    }

    @Test
    void managerShutdownReleasesLoadedTenantBuses() throws Exception {
        String tenantId = "bus-manager-" + System.nanoTime();
        TenantManager manager = new TenantManager(tempDir, new TenantManagerConfig());
        TenantContext tenant = manager.createTenant(TenantProvisioningRequest.builder(tenantId, "test").build());
        tenant.initCollaboration();
        TenantBus first = tenant.getTenantBus();
        first.register("agent", msg -> reply(first, msg, "before-shutdown"));

        assertEquals("before-shutdown", first.sendAndWait(messageTo("agent"), 2_000).getResultText());

        manager.shutdown();

        TenantBus second = TenantBus.forTenant(tenantId);
        second.start();
        assertNotSame(first, second);
        assertFalse(second.isRegistered("agent"));
    }

    private static AgentMessage messageTo(String receiver) {
        return AgentMessage.builder("sender", receiver, AgentMessage.Type.REQUEST)
            .action("ping")
            .payload(Map.of())
            .timeoutMs(2_000)
            .build();
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
