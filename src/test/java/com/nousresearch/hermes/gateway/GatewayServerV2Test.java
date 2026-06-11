package com.nousresearch.hermes.gateway;

import com.alibaba.fastjson2.JSONObject;
import com.nousresearch.hermes.config.HermesConfig;
import com.nousresearch.hermes.tenant.core.TenantManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
import com.nousresearch.hermes.testutil.IsolatedHermesHome;
import org.junit.jupiter.api.extension.RegisterExtension;

class GatewayServerV2Test {

    @RegisterExtension
    final IsolatedHermesHome hermesHome = new IsolatedHermesHome();


    private static class TestAdapter implements PlatformAdapter {
        @Override
        public String getPlatformName() {
            return "test";
        }

        @Override
        public IncomingMessage parseWebhook(JSONObject payload) {
            return null;
        }

        @Override
        public void sendMessage(String channel, String content) {
            // no-op
        }

        @Override
        public void sendReply(String channel, String messageId, String content) {
            // no-op
        }
    }

    @Test
    @DisplayName("GatewayServerV2 should use injected TenantManager")
    void usesInjectedTenantManager() throws Exception {
        TenantManager tenantManager = new TenantManager();
        GatewayServerV2 server = new GatewayServerV2(0, new HermesConfig(), tenantManager);

        Field field = GatewayServerV2.class.getDeclaredField("tenantManager");
        field.setAccessible(true);

        assertSame(tenantManager, field.get(server));
        tenantManager.shutdown();
    }

    @Test
    @DisplayName("Explicit API tenant_id should override auto user tenant mapping")
    void explicitTenantIdOverridesAutoMapping() throws Exception {
        GatewayServerV2 server = new GatewayServerV2(0, new HermesConfig(), new TenantManager());
        IncomingMessage message = new IncomingMessage(
            "msg-1",
            "channel-1",
            "sender@example.com",
            "hello",
            System.currentTimeMillis(),
            false
        );

        Method method = GatewayServerV2.class.getDeclaredMethod(
            "resolveTenantId",
            IncomingMessage.class,
            PlatformAdapter.class,
            String.class
        );
        method.setAccessible(true);

        String resolved = (String) method.invoke(server, message, new TestAdapter(), "team/acme.01");

        assertEquals("team_acme_01", resolved);
        server.stop();
    }
}
