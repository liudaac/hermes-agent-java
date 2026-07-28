package com.nousresearch.hermes.tenant;

import com.nousresearch.hermes.tenant.core.TenantContext;
import com.nousresearch.hermes.tenant.core.TenantAIAgent;
import com.nousresearch.hermes.tenant.core.TenantProvisioningRequest;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import com.nousresearch.hermes.testutil.IsolatedHermesHome;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Unit tests for TenantContext.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class TenantContextTest {

    @RegisterExtension
    final IsolatedHermesHome hermesHome = new IsolatedHermesHome();


    @TempDir
    Path tempDir;

    private TenantContext tenantContext;

    @BeforeEach
    void setUp() {
        TenantProvisioningRequest request = new TenantProvisioningRequest()
            .setTenantId("test-tenant")
            .withDefaultQuota()
            .withDefaultSecurityPolicy();

        tenantContext = TenantContext.create("test-tenant", request);
    }

    @Test
    @Order(1)
    @DisplayName("Tenant should be created with correct ID")
    void testTenantCreation() {
        assertNotNull(tenantContext);
        assertEquals("test-tenant", tenantContext.getTenantId());
        assertEquals(TenantContext.State.ACTIVE, tenantContext.getState());
    }

    @Test
    @Order(2)
    @DisplayName("Tenant quota manager should be initialized")
    void testQuotaManager() {
        assertNotNull(tenantContext.getQuotaManager());
        assertTrue(tenantContext.getQuotaManager().getQuota().getMaxDailyRequests() > 0);
    }

    @Test
    @Order(3)
    @DisplayName("Tenant file sandbox should restrict access")
    void testFileSandbox() {
        assertNotNull(tenantContext.getFileSandbox());

        // Should allow access within tenant directory
        String result = tenantContext.getFileSandbox().writeFile("test.txt", "Hello");
        assertFalse(result.contains("error"));

        // Should deny access outside tenant directory
        String denied = tenantContext.getFileSandbox().readFile("/etc/passwd");
        assertTrue(denied.contains("error") || denied.contains("denied"));
    }

}
