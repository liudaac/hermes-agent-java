package com.nousresearch.hermes.dashboard;

import com.alibaba.fastjson2.JSONObject;
import com.nousresearch.hermes.config.HermesConfig;
import com.nousresearch.hermes.tenant.core.TenantManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import com.nousresearch.hermes.testutil.IsolatedHermesHome;
import org.junit.jupiter.api.extension.RegisterExtension;

class DashboardServerStatusTest {

    @RegisterExtension
    final IsolatedHermesHome hermesHome = new IsolatedHermesHome();


    @Test
    @DisplayName("Dashboard status should reflect injected gateway runtime status")
    void statusReflectsInjectedGatewayRuntimeStatus() {
        TenantManager tenantManager = new TenantManager();
        try {
            DashboardServer server = new DashboardServer(
                0,
                "127.0.0.1",
                new HermesConfig(),
                tenantManager,
                () -> new GatewayRuntimeStatus(
                    true,
                    18088,
                    "RUNNING",
                    "http://127.0.0.1:18088/health",
                    null,
                    123456789L,
                    List.of("telegram", "feishu_comment")
                )
            );

            JSONObject status = server.buildStatus();

            assertTrue(status.getBooleanValue("gateway_running"));
            assertEquals(18088, status.getIntValue("gateway_port"));
            assertEquals("RUNNING", status.getString("gateway_state"));
            assertEquals("http://127.0.0.1:18088/health", status.getString("gateway_health_url"));
            assertEquals("123456789", status.getString("gateway_updated_at"));
            assertTrue(status.getJSONObject("gateway_platforms").getBooleanValue("telegram"));
            assertTrue(status.getJSONObject("gateway_platforms").getBooleanValue("feishu_comment"));
        } finally {
            tenantManager.shutdown();
        }
    }
}
