package com.nousresearch.hermes.tenant.sandbox;

import com.nousresearch.hermes.tenant.core.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * NetworkSandbox 单元测试
 */
class NetworkSandboxTest {

    private TenantContext mockContext;
    private NetworkPolicy policy;
    private RestrictedHttpClient client;

    @BeforeEach
    void setUp() {
        mockContext = mock(TenantContext.class);
        when(mockContext.getTenantId()).thenReturn("test-tenant");
        when(mockContext.getAuditLogger()).thenReturn(mock(com.nousresearch.hermes.tenant.audit.TenantAuditLogger.class));

        policy = NetworkPolicy.builder()
            .allowHost("*.github.com")
            .allowHost("api.openai.com")
            .blockHost("localhost")
            .blockHost("127.0.0.*")
            .blockHost("10.*.*.*")
            .blockHost("192.168.*.*")
            .maxRequestsPerSecond(10)
            .maxRequestBodySize(1024 * 1024) // 1MB
            .maxResponseBodySize(5 * 1024 * 1024) // 5MB
            .build();

        client = new RestrictedHttpClient(mockContext, policy);
    }

    @Test
    void testAllowedHostRequest() {
        // 注意：这是一个集成测试，实际运行需要网络连接
        // 这里我们使用假设测试
        assertTrue(policy.isHostAllowed("api.github.com"));
        assertTrue(policy.isHostAllowed("raw.githubusercontent.com"));
    }

    @Test
    void testBlockedHostRequest() {
        assertFalse(policy.isHostAllowed("localhost"));
        assertFalse(policy.isHostAllowed("127.0.0.1"));
        assertFalse(policy.isHostAllowed("10.0.0.1"));
        assertFalse(policy.isHostAllowed("192.168.1.1"));
    }

    @Test
    void testProtocolRestriction() {
        assertTrue(policy.getAllowedProtocols().contains("https"));
        assertTrue(policy.getAllowedProtocols().contains("http"));
    }

}
