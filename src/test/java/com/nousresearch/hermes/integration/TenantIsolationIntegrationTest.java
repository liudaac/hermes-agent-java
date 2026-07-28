package com.nousresearch.hermes.integration;

import com.nousresearch.hermes.agent.TenantAwareAIAgent;
import com.nousresearch.hermes.config.HermesConfig;
import com.nousresearch.hermes.tenant.core.TenantContext;
import com.nousresearch.hermes.tenant.core.TenantManager;
import com.nousresearch.hermes.tenant.core.TenantProvisioningRequest;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;
import com.nousresearch.hermes.testutil.IsolatedHermesHome;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Integration tests for tenant isolation.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class TenantIsolationIntegrationTest {

    @RegisterExtension
    final IsolatedHermesHome hermesHome = new IsolatedHermesHome();


    private static final String RUN_ID = Long.toHexString(System.nanoTime());

    private TenantManager tenantManager;
    private HermesConfig hermesConfig;

    @BeforeAll
    static void setUpClass() {
        // Initialize test environment
        System.setProperty("hermes.test.mode", "true");
    }

    @BeforeEach
    void setUp() {
        hermesConfig = new HermesConfig(
            System.getenv("OPENAI_API_KEY"),
            "https://api.openai.com/v1",
            "gpt-4"
        );

        tenantManager = new TenantManager();
        tenantManager.initializeDefaultTenant();
    }

    @AfterEach
    void tearDown() {
        if (tenantManager != null) {
            tenantManager.shutdown();
        }
    }

    @Test
    @Order(1)
    @DisplayName("Multiple tenants should be created and managed")
    void testMultipleTenants() {
        // Create multiple tenants
        for (int i = 0; i < 5; i++) {
            String tenantId = unique("tenant-" + i);
            TenantProvisioningRequest request = new TenantProvisioningRequest()
                .setTenantId(tenantId)
                .withDefaultQuota()
                .withDefaultSecurityPolicy();

            TenantContext tenant = tenantManager.createTenant(request);
            assertNotNull(tenant);
            assertEquals(tenantId, tenant.getTenantId());
        }

        // Verify all tenants exist
        assertTrue(tenantManager.exists(unique("tenant-0")));
        assertTrue(tenantManager.exists(unique("tenant-4")));
    }

    @Test
    @Order(2)
    @DisplayName("Tenants should have isolated file systems")
    void testFileSystemIsolation() {
        // Create two tenants
        TenantContext tenant1 = createTestTenant(unique("fs-tenant-1"));
        TenantContext tenant2 = createTestTenant(unique("fs-tenant-2"));

        // Write to same filename in both tenants
        tenant1.getFileSandbox().writeFile("shared.txt", "Tenant1 Data");
        tenant2.getFileSandbox().writeFile("shared.txt", "Tenant2 Data");

        // Verify isolation
        String content1 = tenant1.getFileSandbox().readFile("shared.txt");
        String content2 = tenant2.getFileSandbox().readFile("shared.txt");

        assertTrue(content1.contains("Tenant1"));
        assertTrue(content2.contains("Tenant2"));
        assertNotEquals(content1, content2);
    }

    @Test
    @Order(3)
    @DisplayName("Tenant quota should be enforced")
    void testQuotaEnforcement() {
        TenantContext tenant = createTestTenant(unique("quota-tenant"));

        // Set very low quota
        var quota = tenant.getQuotaManager().getQuota();
        quota.setMaxDailyRequests(1);
        tenant.getQuotaManager().updateQuota(quota);

        // First request should succeed
        try {
            tenant.getQuotaManager().checkDailyRequestQuota();
        } catch (Exception e) {
            fail("First request should succeed");
        }

        // Update usage manually
        tenant.getQuotaManager().getUsage().incrementDailyRequests();

        // Second request should fail
        assertThrows(com.nousresearch.hermes.tenant.quota.QuotaExceededException.class, () -> {
            tenant.getQuotaManager().checkDailyRequestQuota();
        });
    }

    private static String unique(String base) {
        return base + "-" + RUN_ID;
    }

    private TenantContext createTestTenant(String tenantId) {
        TenantProvisioningRequest request = new TenantProvisioningRequest()
            .setTenantId(tenantId)
            .withDefaultQuota()
            .withDefaultSecurityPolicy();

        if (tenantManager.exists(tenantId)) {
            return tenantManager.getTenant(tenantId);
        }

        return tenantManager.createTenant(request);
    }
}
