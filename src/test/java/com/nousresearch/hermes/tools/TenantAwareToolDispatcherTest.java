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

}
