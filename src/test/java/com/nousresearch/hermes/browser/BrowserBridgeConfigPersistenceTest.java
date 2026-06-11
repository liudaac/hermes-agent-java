package com.nousresearch.hermes.browser;

import com.nousresearch.hermes.tenant.core.TenantContext;
import com.nousresearch.hermes.testutil.TestTenants;
import com.nousresearch.hermes.testutil.IsolatedHermesHome;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BrowserBridgeConfigPersistenceTest {
    @RegisterExtension
    final IsolatedHermesHome hermesHome = new IsolatedHermesHome();

    @Test
    void tenantPersistsAndReloadsBrowserBridgeConfig() {
        TenantContext tenant = TestTenants.create("browser-config-persist");
        String tenantId = tenant.getTenantId();
        try {
        tenant.configureBrowserBridge(new BrowserBridgeConfig(
            "kimi",
            "http://127.0.0.1:17361",
            3210,
            "/v1/action",
            "/v1/health",
            "/v1/capabilities"
        ), true);

        var configMap = tenant.getBrowserBridgeConfigMap();
        assertEquals("kimi", configMap.get("provider"));
        assertEquals("/v1/action", configMap.get("action_path"));
        assertTrue(tenant.getBrowserBridge().describe().get("provider").toString().contains("kimi"));

        TenantContext loaded = TenantContext.load(tenantId);
        var loadedConfig = loaded.getBrowserBridgeConfig();
        assertNotNull(loadedConfig);
        assertEquals("kimi", loadedConfig.provider());
        assertEquals("http://127.0.0.1:17361", loadedConfig.endpoint());
        assertEquals(3210, loadedConfig.timeoutMs());
        assertEquals("/v1/action", loadedConfig.actionPath());
        assertEquals("/v1/health", loadedConfig.healthPath());
        assertEquals("/v1/capabilities", loadedConfig.capabilitiesPath());
        assertTrue(loaded.getBrowserBridge().describe().get("provider").toString().contains("kimi"));
        } finally { TestTenants.cleanup(tenantId); }
    }
}
