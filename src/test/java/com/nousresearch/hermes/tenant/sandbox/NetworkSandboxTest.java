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
        when(mockContext.getAuditLogger()).thenReturn(mock(com.nousresearch.hermes.tenant.core.TenantAuditLogger.class));

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

    @Test
    void testRequestSizeLimit() {
        // 请求体超过限制应该被拒绝
        String largeBody = "x".repeat((int) (policy.getMaxRequestBodySize() + 1));
        
        NetworkSandboxException exception = assertThrows(
            NetworkSandboxException.class,
            () -> client.post("https://api.github.com/test", largeBody)
        );

        assertTrue(exception.getMessage().contains("too large"));
    }

    @Test
    void testRateLimit() {
        // 快速发送请求应该触发速率限制
        int requestCount = 0;
        for (int i = 0; i < 15; i++) {
            try {
                client.get("https://api.github.com/");
                requestCount++;
            } catch (NetworkSandboxException e) {
                assertTrue(e.getMessage().contains("Rate limit"));
                break;
            }
        }
        
        // 应该在前 10 个请求后触发限制
        assertTrue(requestCount <= 10);
    }

    @Test
    void testNetworkStats() {
        RestrictedHttpClient.NetworkStats stats = client.getStats();
        
        assertNotNull(stats);
        assertEquals(0, stats.getTotalRequests()); // 初始状态
        assertEquals(0, stats.getBlockedRequests());
    }
}
