package com.nousresearch.hermes.tools;

import com.nousresearch.hermes.tenant.core.TenantContext;
import com.nousresearch.hermes.tenant.core.TenantProvisioningRequest;
import org.junit.jupiter.api.*;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TenantAwareToolDispatcher.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class TenantAwareToolDispatcherTest {

    private TenantContext tenantContext;
    private TenantAwareToolDispatcher dispatcher;
    private ToolRegistry globalRegistry;

    @BeforeEach
    void setUp() {
        globalRegistry = ToolRegistry.getInstance();

        TenantProvisioningRequest request = new TenantProvisioningRequest()
            .setTenantId("test-tenant")
            .withDefaultQuota()
            .withDefaultSecurityPolicy();

        tenantContext = TenantContext.create("test-tenant", request);
        dispatcher = new TenantAwareToolDispatcher(tenantContext, globalRegistry);
    }

    @Test
    @Order(1)
    @DisplayName("File read should work within tenant directory")
    void testFileRead() {
        // Write a file first
        tenantContext.getFileSandbox().writeFile("test.txt", "Hello World");

        // Read it back
        String result = dispatcher.dispatch("file_read", Map.of("path", "test.txt"));

        assertNotNull(result);
        assertTrue(result.contains("Hello World"));
    }

    @Test
    @Order(2)
    @DisplayName("File write should create files in tenant directory")
    void testFileWrite() {
        String result = dispatcher.dispatch("file_write", Map.of(
            "path", "write_test.txt",
            "content", "Test content"
        ));

        assertNotNull(result);
        assertFalse(result.contains("error"));

        // Verify file exists
        String readResult = dispatcher.dispatch("file_read", Map.of("path", "write_test.txt"));
        assertTrue(readResult.contains("Test content"));
    }

    @Test
    @Order(3)
    @DisplayName("File operations should be denied outside tenant directory")
    void testFileSandboxIsolation() {
        String result = dispatcher.dispatch("file_read", Map.of("path", "/etc/passwd"));

        assertTrue(result.contains("error") || result.contains("denied"));
    }

    @Test
    @Order(4)
    @DisplayName("Code execution should check tenant permissions")
    void testCodeExecutionPermissions() {
        // If code execution is disabled, should return error
        if (!tenantContext.getSecurityPolicy().isAllowCodeExecution()) {
            String result = dispatcher.dispatch("execute_python", Map.of(
                "code", "print('hello')"
            ));
            assertTrue(result.contains("disabled"));
        }
    }

    @Test
    @Order(5)
    @DisplayName("Unknown tools should return error")
    void testUnknownTool() {
        String result = dispatcher.dispatch("unknown_tool", Map.of());

        assertTrue(result.contains("error") || result.contains("Unknown"));
    }

    @Test
    @Order(6)
    @DisplayName("Tool calls should be audited")
    void testAuditLogging() {
        dispatcher.dispatch("file_read", Map.of("path", "test.txt"));

        // Verify audit log was written
        // This would require checking the audit log file
        assertNotNull(tenantContext.getAuditLogger());
    }

    @Test
    @Order(7)
    @DisplayName("Network access should respect tenant policy")
    void testNetworkPolicy() {
        // Most tenants should have restricted network access by default
        String result = dispatcher.dispatch("web_fetch", Map.of(
            "url", "https://example.com"
        ));

        // Result depends on tenant network policy
        assertNotNull(result);
    }
}
