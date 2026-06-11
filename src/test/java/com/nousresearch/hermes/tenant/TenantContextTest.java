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

    @Test
    @Order(4)
    @DisplayName("Tenant security policy should be enforced")
    void testSecurityPolicy() {
        assertNotNull(tenantContext.getSecurityPolicy());

        // Test allowed tools
        assertTrue(tenantContext.getSecurityPolicy().getAllowedTools().isEmpty() ||
                   tenantContext.getSecurityPolicy().getAllowedTools().contains("file_read"));
    }

    @Test
    @Order(5)
    @DisplayName("Tenant session management should work")
    void testSessionManagement() {
        assertNotNull(tenantContext.getSessionManager());

        // Create a session
        var session = tenantContext.getSessionManager().createSession("test-session");
        assertNotNull(session);

        // Session count should increase
        int count = tenantContext.getActiveSessionCount();
        assertTrue(count >= 1);
    }

    @Test
    @Order(6)
    @DisplayName("Tenant state transitions should work")
    void testStateTransitions() {
        assertEquals(TenantContext.State.ACTIVE, tenantContext.getState());

        // Suspend tenant
        tenantContext.suspend("Test suspension");
        assertEquals(TenantContext.State.SUSPENDED, tenantContext.getState());

        // Resume tenant
        tenantContext.resume();
        assertEquals(TenantContext.State.ACTIVE, tenantContext.getState());
    }

    @Test
    @Order(7)
    @DisplayName("Tenant isolation should prevent cross-tenant access")
    void testTenantIsolation() {
        TenantProvisioningRequest request2 = new TenantProvisioningRequest()
            .setTenantId("other-tenant")
            .withDefaultQuota()
            .withDefaultSecurityPolicy();

        TenantContext otherTenant = TenantContext.create("other-tenant", request2);

        // Each tenant should have independent file sandbox
        tenantContext.getFileSandbox().writeFile("test.txt", "Tenant1");
        otherTenant.getFileSandbox().writeFile("test.txt", "Tenant2");

        // Files should be isolated
        String content1 = tenantContext.getFileSandbox().readFile("test.txt");
        String content2 = otherTenant.getFileSandbox().readFile("test.txt");

        assertNotEquals(content1, content2);
    }

    @Test
    @Order(8)
    @DisplayName("TenantAIAgent should reuse existing TenantContext and session ID")
    void testTenantAIAgentUsesExistingContextAndSession() throws Exception {
        String sessionId = "gateway-session-123";
        TenantAIAgent agent = tenantContext.getOrCreateAgent(sessionId);
        assertNotNull(agent);
        assertEquals(sessionId, agent.getSessionId());

        var delegateField = TenantAIAgent.class.getDeclaredField("delegate");
        delegateField.setAccessible(true);
        Object delegate = delegateField.get(agent);

        var tenantContextField = delegate.getClass().getDeclaredField("tenantContext");
        tenantContextField.setAccessible(true);
        Object delegateContext = tenantContextField.get(delegate);
        assertSame(tenantContext, delegateContext, "Delegate should use the already-created tenant context");

        var sessionField = delegate.getClass().getDeclaredField("sessionId");
        sessionField.setAccessible(true);
        assertEquals(sessionId, sessionField.get(delegate), "Delegate should preserve gateway session ID");
    }

}
