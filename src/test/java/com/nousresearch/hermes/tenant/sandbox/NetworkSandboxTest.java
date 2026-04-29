package com.nousresearch.hermes.tenant.sandbox;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

import java.net.http.HttpResponse;

/**
 * NetworkSandbox 单元测试
 */
public class NetworkSandboxTest {

    private NetworkSandbox sandbox;

    @BeforeEach
    void setUp() {
        NetworkPolicy policy = NetworkPolicy.builder()
            .allowHost("*.github.com")
            .allowHost("httpbin.org")
            .blockHost("localhost")
            .blockHost("127.0.0.*")
            .blockHost("10.*.*.*")
            .maxRequestsPerSecond(100)
            .build();
        
        sandbox = new NetworkSandbox(policy);
    }

    @Test
    @DisplayName("访问白名单域名应该成功")
    void testAllowedHost() {
        // 注意：实际测试需要网络连接
        // 这里使用 httpbin.org 进行测试
        assertDoesNotThrow(() -> {
            HttpResponse<String> response = sandbox.get("https://httpbin.org/get");
            assertEquals(200, response.statusCode());
        });
    }

    @Test
    @DisplayName("访问黑名单域名应该被拒绝")
    void testBlockedHost() {
        NetworkSandboxException exception = assertThrows(
            NetworkSandboxException.class,
            () -> sandbox.get("http://localhost:8080/api")
        );
        
        assertTrue(exception.getMessage().contains("Host not allowed"));
    }

    @Test
    @DisplayName("访问内网 IP 应该被拒绝")
    void testBlockedInternalIP() {
        NetworkSandboxException exception = assertThrows(
            NetworkSandboxException.class,
            () -> sandbox.get("http://10.0.0.1/api")
        );
        
        assertTrue(exception.getMessage().contains("Host not allowed"));
    }

    @Test
    @DisplayName("不允许的协议应该被拒绝")
    void testDisallowedProtocol() {
        NetworkPolicy strictPolicy = NetworkPolicy.builder()
            .allowedProtocols(Set.of("https"))  // 只允许 https
            .build();
        
        NetworkSandbox strictSandbox = new NetworkSandbox(strictPolicy);
        
        NetworkSandboxException exception = assertThrows(
            NetworkSandboxException.class,
            () -> strictSandbox.get("ftp://example.com/file")
        );
        
        assertTrue(exception.getMessage().contains("Protocol not allowed"));
    }

    @Test
    @DisplayName("速率限制应该生效")
    void testRateLimit() {
        NetworkPolicy limitedPolicy = NetworkPolicy.builder()
            .allowHost("httpbin.org")
            .maxRequestsPerSecond(1)  // 每秒只允许1个请求
            .build();
        
        NetworkSandbox limitedSandbox = new NetworkSandbox(limitedPolicy);
        
        // 第一个请求应该成功
        assertDoesNotThrow(() -> limitedSandbox.get("https://httpbin.org/get"));
        
        // 立即发送第二个请求应该被限流
        assertThrows(NetworkQuotaExceededException.class, () -> {
            limitedSandbox.get("https://httpbin.org/get");
        });
    }

    @Test
    @DisplayName("POST 请求应该正常工作")
    void testPostRequest() {
        assertDoesNotThrow(() -> {
            HttpResponse<String> response = sandbox.post(
                "https://httpbin.org/post",
                "{\"test\": \"data\"}"
            );
            assertEquals(200, response.statusCode());
        });
    }
}
