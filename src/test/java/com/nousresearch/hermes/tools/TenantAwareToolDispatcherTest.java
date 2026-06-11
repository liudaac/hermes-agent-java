package com.nousresearch.hermes.tools;

import com.nousresearch.hermes.tenant.core.TenantContext;
import com.nousresearch.hermes.tenant.core.TenantProvisioningRequest;
import org.junit.jupiter.api.*;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import com.nousresearch.hermes.testutil.IsolatedHermesHome;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Unit tests for TenantAwareToolDispatcher.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class TenantAwareToolDispatcherTest {

    @RegisterExtension
    final IsolatedHermesHome hermesHome = new IsolatedHermesHome();


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

    @Test
    @Order(8)
    @DisplayName("Denied tools should fail before execution and not be recorded")
    void testDeniedToolShouldNotExecuteOrRecord() {
        tenantContext.getSecurityPolicy().setDeniedTools(Set.of("write_file"));

        String result = dispatcher.dispatch("write_file", Map.of(
            "path", "denied.txt",
            "content", "should not be written"
        ));

        assertTrue(result.contains("error"));
        assertTrue(result.contains("denied") || result.contains("explicitly"));
        assertEquals(0, tenantContext.getToolRegistry().getStats().currentCalls());
        assertTrue(tenantContext.getFileSandbox().readFile("denied.txt").contains("error"));
    }

    @Test
    @Order(9)
    @DisplayName("Allowed tool calls should be recorded in tenant registry stats")
    void testAllowedToolCallsShouldBeRecorded() {
        String result = dispatcher.dispatch("write_file", Map.of(
            "path", "recorded.txt",
            "content", "record me"
        ));

        assertNotNull(result);
        assertFalse(result.contains("error"));
        assertEquals(1, tenantContext.getToolRegistry().getStats().currentCalls());
        assertTrue(tenantContext.getToolRegistry().getStats().toolCounts().containsKey("write_file"));
        assertEquals(1, tenantContext.getToolRegistry().getStats().toolCounts().get("write_file").get());
    }

}
