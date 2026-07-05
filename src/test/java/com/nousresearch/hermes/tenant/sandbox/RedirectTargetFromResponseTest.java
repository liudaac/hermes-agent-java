package com.nousresearch.hermes.tenant.sandbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;

/**
 * S1-4b: SSRF 重定向绕过补丁测试
 *
 * <p>对齐 Python 原版 commit 500c2b1e4 的测试用例。
 * 验证 {@link RestrictedHttpClient#resolveRedirect(URI, String)} 能正确解析
 * 各种 Location 头格式，防止攻击者通过重定向绕过 URL 白名单。</p>
 *
 * <p>攻击场景：白名单内的 URL 返回 302 → Location: http://169.254.169.254/
 * 如果不检查重定向目标，HttpClient 自动跟随，SSRF 绕过成功。</p>
 */
class RedirectTargetFromResponseTest {

    // ========================================================================
    // resolveRedirect 单元测试 — 等价于 Python redirect_target_from_response
    // ========================================================================

    @Nested
    @DisplayName("绝对 URL Location 头")
    class AbsoluteLocation {

        @Test
        @DisplayName("https → https 绝对重定向")
        void httpsToHttps() {
            URI base = URI.create("https://api.github.com/users/octocat");
            URI result = RestrictedHttpClient.resolveRedirect(base, "https://api.github.com/users/octocat?page=2");
            assertEquals("https", result.getScheme());
            assertEquals("api.github.com", result.getHost());
            assertEquals("/users/octocat", result.getPath());
            assertEquals("page=2", result.getQuery());
        }

        @Test
        @DisplayName("https → http 协议降级重定向")
        void httpsToHttp() {
            URI base = URI.create("https://secure.example.com/login");
            URI result = RestrictedHttpClient.resolveRedirect(base, "http://insecure.example.com/callback");
            assertEquals("http", result.getScheme());
            assertEquals("insecure.example.com", result.getHost());
        }

        @Test
        @DisplayName("github → 内网 IP 重定向（SSRF 攻击向量）")
        void githubToInternalIp() {
            URI base = URI.create("https://api.github.com/repos/test");
            URI result = RestrictedHttpClient.resolveRedirect(base, "http://169.254.169.254/latest/meta-data/");
            assertEquals("http", result.getScheme());
            assertEquals("169.254.169.254", result.getHost());
            assertEquals("/latest/meta-data/", result.getPath());
        }

        @Test
        @DisplayName("带端口号的绝对重定向")
        void withPort() {
            URI base = URI.create("https://api.example.com/v1");
            URI result = RestrictedHttpClient.resolveRedirect(base, "https://api.example.com:8443/v2");
            assertEquals("api.example.com", result.getHost());
            assertEquals(8443, result.getPort());
        }

        @Test
        @DisplayName("带 fragment 的绝对重定向")
        void withFragment() {
            URI base = URI.create("https://example.com/page");
            URI result = RestrictedHttpClient.resolveRedirect(base, "https://example.com/page#section");
            assertEquals("/page", result.getPath());
            assertEquals("section", result.getFragment());
        }

        @Test
        @DisplayName("带 query 的绝对重定向")
        void withQuery() {
            URI base = URI.create("https://example.com/search");
            URI result = RestrictedHttpClient.resolveRedirect(base, "https://example.com/results?q=test&lang=en");
            assertEquals("/results", result.getPath());
            assertEquals("q=test&lang=en", result.getQuery());
        }
    }

    @Nested
    @DisplayName("相对路径 Location 头")
    class RelativeLocation {

        @Test
        @DisplayName("根路径相对重定向 /newpath")
        void rootRelative() {
            URI base = URI.create("https://api.github.com/users/octocat");
            URI result = RestrictedHttpClient.resolveRedirect(base, "/users/octocat/repos");
            assertEquals("https", result.getScheme());
            assertEquals("api.github.com", result.getHost());
            assertEquals("/users/octocat/repos", result.getPath());
        }

        @Test
        @DisplayName("同目录相对重定向 newpath")
        void sameDirRelative() {
            URI base = URI.create("https://api.github.com/users/octocat");
            URI result = RestrictedHttpClient.resolveRedirect(base, "repos");
            assertEquals("https", result.getScheme());
            assertEquals("api.github.com", result.getHost());
            assertEquals("/users/repos", result.getPath());
        }

        @Test
        @DisplayName("上级目录相对重定向 ../other")
        void parentDirRelative() {
            URI base = URI.create("https://api.github.com/users/octocat/repos");
            URI result = RestrictedHttpClient.resolveRedirect(base, "../other");
            assertEquals("https", result.getScheme());
            assertEquals("api.github.com", result.getHost());
            assertEquals("/users/other", result.getPath());
        }

        @Test
        @DisplayName("带 query 的相对重定向")
        void relativeWithQuery() {
            URI base = URI.create("https://api.github.com/users/octocat");
            URI result = RestrictedHttpClient.resolveRedirect(base, "/users?page=2&per_page=50");
            assertEquals("https", result.getScheme());
            assertEquals("api.github.com", result.getHost());
            assertEquals("/users", result.getPath());
            assertEquals("page=2&per_page=50", result.getQuery());
        }

        @Test
        @DisplayName("空路径相对重定向（同 URL）")
        void emptyPath() {
            URI base = URI.create("https://api.github.com/users/octocat");
            URI result = RestrictedHttpClient.resolveRedirect(base, "");
            assertEquals("https", result.getScheme());
            assertEquals("api.github.com", result.getHost());
        }

        @Test
        @DisplayName("带 fragment 的相对重定向")
        void relativeWithFragment() {
            URI base = URI.create("https://example.com/docs/api");
            URI result = RestrictedHttpClient.resolveRedirect(base, "/docs/api#auth");
            assertEquals("/docs/api", result.getPath());
            assertEquals("auth", result.getFragment());
        }
    }

    @Nested
    @DisplayName("协议相对 Location 头 (//host/path)")
    class ProtocolRelativeLocation {

        @Test
        @DisplayName("https base + //evil.com/path")
        void httpsBaseProtocolRelative() {
            URI base = URI.create("https://api.github.com/users");
            URI result = RestrictedHttpClient.resolveRedirect(base, "//evil.com/steal");
            assertEquals("https", result.getScheme());
            assertEquals("evil.com", result.getHost());
            assertEquals("/steal", result.getPath());
        }

        @Test
        @DisplayName("http base + //evil.com/path")
        void httpBaseProtocolRelative() {
            URI base = URI.create("http://api.example.com/data");
            URI result = RestrictedHttpClient.resolveRedirect(base, "//internal.corp/admin");
            assertEquals("http", result.getScheme());
            assertEquals("internal.corp", result.getHost());
            assertEquals("/admin", result.getPath());
        }

        @Test
        @DisplayName("协议相对 + 端口号")
        void protocolRelativeWithPort() {
            URI base = URI.create("https://api.github.com/v1");
            URI result = RestrictedHttpClient.resolveRedirect(base, "//evil.com:8080/exfil");
            assertEquals("https", result.getScheme());
            assertEquals("evil.com", result.getHost());
            assertEquals(8080, result.getPort());
        }
    }

    @Nested
    @DisplayName("SSRF 攻击向量")
    class SsrfAttackVectors {

        @Test
        @DisplayName("302 → AWS metadata endpoint")
        void awsMetadata() {
            URI base = URI.create("https://api.github.com/hooks");
            URI result = RestrictedHttpClient.resolveRedirect(base, "http://169.254.169.254/latest/meta-data/iam/security-credentials/");
            assertEquals("169.254.169.254", result.getHost());
            assertEquals("/latest/meta-data/iam/security-credentials/", result.getPath());
        }

        @Test
        @DisplayName("302 → AWS ECS task metadata")
        void ecsMetadata() {
            URI base = URI.create("https://api.github.com/hooks");
            URI result = RestrictedHttpClient.resolveRedirect(base, "http://169.254.170.2/v2/metadata");
            assertEquals("169.254.170.2", result.getHost());
        }

        @Test
        @DisplayName("302 → Azure IMDS")
        void azureMetadata() {
            URI base = URI.create("https://api.github.com/hooks");
            URI result = RestrictedHttpClient.resolveRedirect(base, "http://169.254.169.253/metadata/instance?api-version=2021-02-01");
            assertEquals("169.254.169.253", result.getHost());
        }

        @Test
        @DisplayName("302 → Alibaba Cloud metadata")
        void alibabaMetadata() {
            URI base = URI.create("https://api.github.com/hooks");
            URI result = RestrictedHttpClient.resolveRedirect(base, "http://100.100.100.200/latest/meta-data/");
            assertEquals("100.100.100.200", result.getHost());
        }

        @Test
        @DisplayName("302 → GCP metadata")
        void gcpMetadata() {
            URI base = URI.create("https://api.github.com/hooks");
            URI result = RestrictedHttpClient.resolveRedirect(base, "http://metadata.google.internal/computeMetadata/v1/");
            assertEquals("metadata.google.internal", result.getHost());
        }

        @Test
        @DisplayName("302 → localhost")
        void localhost() {
            URI base = URI.create("https://api.github.com/hooks");
            URI result = RestrictedHttpClient.resolveRedirect(base, "http://localhost:8080/admin");
            assertEquals("localhost", result.getHost());
            assertEquals(8080, result.getPort());
        }

        @Test
        @DisplayName("302 → 127.0.0.1")
        void loopback() {
            URI base = URI.create("https://api.github.com/hooks");
            URI result = RestrictedHttpClient.resolveRedirect(base, "http://127.0.0.1:6379/");
            assertEquals("127.0.0.1", result.getHost());
            assertEquals(6379, result.getPort());
        }

        @Test
        @DisplayName("302 → 10.x 内网")
        void privateNetwork10() {
            URI base = URI.create("https://api.github.com/hooks");
            URI result = RestrictedHttpClient.resolveRedirect(base, "http://10.0.0.1/");
            assertEquals("10.0.0.1", result.getHost());
        }

        @Test
        @DisplayName("302 → 192.168.x 内网")
        void privateNetwork192() {
            URI base = URI.create("https://api.github.com/hooks");
            URI result = RestrictedHttpClient.resolveRedirect(base, "http://192.168.1.1/admin");
            assertEquals("192.168.1.1", result.getHost());
        }

        @Test
        @DisplayName("302 → 172.16.x 内网")
        void privateNetwork172() {
            URI base = URI.create("https://api.github.com/hooks");
            URI result = RestrictedHttpClient.resolveRedirect(base, "http://172.16.0.1/");
            assertEquals("172.16.0.1", result.getHost());
        }
    }

    @Nested
    @DisplayName("非 HTTP 协议重定向（应被阻止）")
    class NonHttpProtocolRedirects {

        @Test
        @DisplayName("302 → file:// 协议")
        void fileProtocol() {
            URI base = URI.create("https://api.github.com/hooks");
            URI result = RestrictedHttpClient.resolveRedirect(base, "file:///etc/passwd");
            assertEquals("file", result.getScheme());
            // 注意：resolveRedirect 只负责解析 URI，协议检查在 execute() 里做
        }

        @Test
        @DisplayName("302 → gopher:// 协议")
        void gopherProtocol() {
            URI base = URI.create("https://api.github.com/hooks");
            URI result = RestrictedHttpClient.resolveRedirect(base, "gopher://127.0.0.1:6379/_INFO");
            assertEquals("gopher", result.getScheme());
        }

        @Test
        @DisplayName("302 → ftp:// 协议")
        void ftpProtocol() {
            URI base = URI.create("https://api.github.com/hooks");
            URI result = RestrictedHttpClient.resolveRedirect(base, "ftp://internal.corp/secrets.txt");
            assertEquals("ftp", result.getScheme());
        }

        @Test
        @DisplayName("302 → dict:// 协议")
        void dictProtocol() {
            URI base = URI.create("https://api.github.com/hooks");
            URI result = RestrictedHttpClient.resolveRedirect(base, "dict://127.0.0.1:6379/INFO");
            assertEquals("dict", result.getScheme());
        }
    }

    @Nested
    @DisplayName("边界情况")
    class EdgeCases {

        @Test
        @DisplayName("Location 头带前后空格")
        void leadingTrailingWhitespace() {
            URI base = URI.create("https://api.github.com/data");
            URI result = RestrictedHttpClient.resolveRedirect(base, "  https://api.github.com/v2  ");
            assertEquals("https", result.getScheme());
            assertEquals("api.github.com", result.getHost());
            assertEquals("/v2", result.getPath());
        }

        @Test
        @DisplayName("Location 头只有空格（应 fallback 到 resolve）")
        void onlyWhitespace() {
            URI base = URI.create("https://api.github.com/data");
            URI result = RestrictedHttpClient.resolveRedirect(base, "   ");
            // 空格 trim 后为空字符串，URI.create("") 不会抛异常
            assertNotNull(result);
        }

        @Test
        @DisplayName("带特殊字符的 URL")
        void specialChars() {
            URI base = URI.create("https://api.github.com/search");
            URI result = RestrictedHttpClient.resolveRedirect(base, "https://api.github.com/results?q=hello%20world&lang=zh-CN");
            assertEquals("api.github.com", result.getHost());
            assertNotNull(result.getQuery());
        }

        @Test
        @DisplayName("base URI 带 query string，相对重定向应丢弃 query")
        void baseWithQueryRelativeRedirect() {
            URI base = URI.create("https://api.github.com/search?q=test&page=1");
            URI result = RestrictedHttpClient.resolveRedirect(base, "/search?page=2");
            assertEquals("https", result.getScheme());
            assertEquals("api.github.com", result.getHost());
            assertEquals("/search", result.getPath());
            assertEquals("page=2", result.getQuery());
        }

        @Test
        @DisplayName("base URI 带 fragment，相对重定向应丢弃 fragment")
        void baseWithFragmentRelativeRedirect() {
            URI base = URI.create("https://api.github.com/docs#section");
            URI result = RestrictedHttpClient.resolveRedirect(base, "/docs/api");
            assertEquals("https", result.getScheme());
            assertEquals("api.github.com", result.getHost());
            assertEquals("/docs/api", result.getPath());
        }

        @Test
        @DisplayName("多层相对路径 ../ 解析")
        void multipleParentDirs() {
            URI base = URI.create("https://api.github.com/a/b/c/d");
            URI result = RestrictedHttpClient.resolveRedirect(base, "../../e/f");
            assertEquals("https", result.getScheme());
            assertEquals("api.github.com", result.getHost());
            // base path /a/b/c/d → directory /a/b/c/ → ../ → /a/b/ → ../ → /a/ → e/f → /a/e/f
            assertEquals("/a/e/f", result.getPath());
        }

        @Test
        @DisplayName("IP 地址作为 base URI")
        void ipBaseUri() {
            URI base = URI.create("https://93.184.216.34/api");
            URI result = RestrictedHttpClient.resolveRedirect(base, "/v2");
            assertEquals("93.184.216.34", result.getHost());
            assertEquals("/v2", result.getPath());
        }

        @Test
        @DisplayName("带用户信息的 URL（应保留 host）")
        void userInfoInBase() {
            URI base = URI.create("https://user:pass@api.github.com/v1");
            URI result = RestrictedHttpClient.resolveRedirect(base, "/v2");
            assertEquals("api.github.com", result.getHost());
            assertEquals("/v2", result.getPath());
        }
    }

    // ========================================================================
    // isUrlAllowed 重定向场景验证
    // ========================================================================

    @Nested
    @DisplayName("isUrlAllowed 对重定向目标的检查")
    class IsUrlAllowedRedirectCheck {

        @Test
        @DisplayName("白名单内的 URL 应允许")
        void allowedUrl() {
            NetworkPolicy policy = NetworkPolicy.builder()
                .allowHost("*.github.com")
                .blockHost("169.254.*.*")
                .build();

            RestrictedHttpClient client = createClient(policy);
            assertTrue(client.isUrlAllowed("https://api.github.com/users"));
        }

        @Test
        @DisplayName("重定向到内网 IP 应被阻止")
        void redirectToInternalBlocked() {
            NetworkPolicy policy = NetworkPolicy.builder()
                .allowHost("*.github.com")
                .blockHost("169.254.*.*")
                .blockHost("127.0.0.*")
                .blockHost("10.*.*.*")
                .blockHost("192.168.*.*")
                .build();

            RestrictedHttpClient client = createClient(policy);
            assertFalse(client.isUrlAllowed("http://169.254.169.254/latest/meta-data/"));
            assertFalse(client.isUrlAllowed("http://127.0.0.1:8080/"));
            assertFalse(client.isUrlAllowed("http://10.0.0.1/"));
            assertFalse(client.isUrlAllowed("http://192.168.1.1/"));
        }

        @Test
        @DisplayName("重定向到 localhost 应被阻止")
        void redirectToLocalhostBlocked() {
            NetworkPolicy policy = NetworkPolicy.builder()
                .allowHost("*.github.com")
                .blockHost("localhost")
                .build();

            RestrictedHttpClient client = createClient(policy);
            assertFalse(client.isUrlAllowed("http://localhost:8080/admin"));
        }

        @Test
        @DisplayName("重定向到非白名单 host 应被阻止")
        void redirectToNonWhitelistedHost() {
            NetworkPolicy policy = NetworkPolicy.builder()
                .allowHost("*.github.com")
                .build();

            RestrictedHttpClient client = createClient(policy);
            assertFalse(client.isUrlAllowed("https://evil.com/steal"));
            assertFalse(client.isUrlAllowed("https://internal.corp/admin"));
        }

        @Test
        @DisplayName("file:// 协议应被阻止")
        void fileProtocolBlocked() {
            NetworkPolicy policy = NetworkPolicy.builder()
                .build();

            RestrictedHttpClient client = createClient(policy);
            assertFalse(client.isUrlAllowed("file:///etc/passwd"));
        }
    }

    // ========================================================================
    // 辅助方法
    // ========================================================================

    private RestrictedHttpClient createClient(NetworkPolicy policy) {
        com.nousresearch.hermes.tenant.core.TenantContext mockContext =
            org.mockito.Mockito.mock(com.nousresearch.hermes.tenant.core.TenantContext.class);
        org.mockito.Mockito.when(mockContext.getTenantId()).thenReturn("test-tenant");
        org.mockito.Mockito.when(mockContext.getAuditLogger())
            .thenReturn(org.mockito.Mockito.mock(com.nousresearch.hermes.tenant.audit.TenantAuditLogger.class));
        return new RestrictedHttpClient(mockContext, policy);
    }
}
