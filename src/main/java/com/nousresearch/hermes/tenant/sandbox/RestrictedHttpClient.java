package com.nousresearch.hermes.tenant.sandbox;

import com.nousresearch.hermes.tenant.core.TenantContext;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 受限的 HTTP 客户端
 * 
 * 提供透明的网络访问控制：
 * - 所有 HTTP 请求必须经过此类
 * - 自动检查 URL 白名单/黑名单
 * - 速率限制（每秒请求数）
 * - 请求/响应大小限制
 * - 完整的审计日志
 * - SSRF 防护：手动重定向跟随，每个跳点都过 isUrlAllowed()
 *
 * <p>S1-4b 补丁（对齐 Python 原版 commit 500c2b1e4）：
 * 不再使用 HttpClient.Redirect.NORMAL 自动跟随重定向，
 * 因为自动跟随时 isUrlAllowed() 只检查初始 URL，
 * 攻击者可用白名单 URL 发起请求 + 302 到内网地址绕过白名单。
 * 改为 Redirect.NEVER + 手动解析 Location 头 + urljoin 解析 + 逐跳安全检查。</p>
 */
public class RestrictedHttpClient {

    /** 最大重定向跳数（对齐 Python httpx 默认值） */
    private static final int MAX_REDIRECTS = 5;

    /** 重定向状态码 */
    private static final java.util.Set<Integer> REDIRECT_STATUS_CODES = java.util.Set.of(
        301, 302, 303, 307, 308
    );

    private final String tenantId;
    private final NetworkPolicy policy;
    private final RateLimiter rateLimiter;
    private final HttpClient httpClient;
    private final TenantContext context;

    // 统计信息
    private final AtomicInteger requestCount = new AtomicInteger(0);
    private final AtomicInteger blockedCount = new AtomicInteger(0);
    private final Map<String, AtomicInteger> hostRequestCount = new ConcurrentHashMap<>();

    public RestrictedHttpClient(TenantContext context, NetworkPolicy policy) {
        this.tenantId = context.getTenantId();
        this.context = context;
        this.policy = policy != null ? policy : NetworkPolicy.defaultPolicy();
        this.rateLimiter = new RateLimiter(this.policy.getMaxRequestsPerSecond());
        
        // S1-4b: 始终用 NEVER，手动处理重定向以确保每个跳点都过安全检查
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(this.policy.getConnectTimeoutSeconds()))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    }

    /**
     * GET 请求
     */
    public HttpResponse<String> get(String url) throws NetworkSandboxException {
        return get(url, Map.of());
    }

    /**
     * GET 请求（带请求头）
     */
    public HttpResponse<String> get(String url, Map<String, String> headers) throws NetworkSandboxException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .GET()
            .timeout(Duration.ofSeconds(policy.getRequestTimeoutSeconds()));

        headers.forEach(builder::header);

        return execute(builder.build());
    }

    /**
     * POST 请求
     */
    public HttpResponse<String> post(String url, String body) throws NetworkSandboxException {
        return post(url, body, "application/json");
    }

    /**
     * POST 请求（带 Content-Type）
     */
    public HttpResponse<String> post(String url, String body, String contentType) throws NetworkSandboxException {
        // 检查请求体大小
        if (body != null && body.length() > policy.getMaxRequestBodySize()) {
            throw new NetworkSandboxException(
                "Request body too large: " + body.length() + " bytes (max: " + policy.getMaxRequestBodySize() + ")"
            );
        }

        HttpRequest.BodyPublisher bodyPublisher = body != null 
            ? HttpRequest.BodyPublishers.ofString(body)
            : HttpRequest.BodyPublishers.noBody();

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", contentType)
            .POST(bodyPublisher)
            .timeout(Duration.ofSeconds(policy.getRequestTimeoutSeconds()))
            .build();

        return execute(request);
    }

    /**
     * PUT 请求
     */
    public HttpResponse<String> put(String url, String body) throws NetworkSandboxException {
        HttpRequest.BodyPublisher bodyPublisher = body != null 
            ? HttpRequest.BodyPublishers.ofString(body)
            : HttpRequest.BodyPublishers.noBody();

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .PUT(bodyPublisher)
            .timeout(Duration.ofSeconds(policy.getRequestTimeoutSeconds()))
            .build();

        return execute(request);
    }

    /**
     * DELETE 请求
     */
    public HttpResponse<String> delete(String url) throws NetworkSandboxException {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .DELETE()
            .timeout(Duration.ofSeconds(policy.getRequestTimeoutSeconds()))
            .build();

        return execute(request);
    }

    /**
     * 执行 HTTP 请求（核心方法）
     * 
     * S1-4b: 手动处理重定向，每个跳点都过 isUrlAllowed() 安全检查。
     */
    private HttpResponse<String> execute(HttpRequest request) throws NetworkSandboxException {
        URI currentUri = request.uri();
        String method = request.method();
        int redirectCount = 0;

        while (true) {
            String url = currentUri.toString();

            // 1. 检查 URL 是否允许（每个跳点都检查）
            if (!isUrlAllowed(url)) {
                blockedCount.incrementAndGet();
                logBlockedRequest(url, "URL not in whitelist");
                throw new NetworkSandboxException("URL not allowed: " + url);
            }

            // 2. 检查速率限制
            if (!rateLimiter.tryAcquire()) {
                blockedCount.incrementAndGet();
                logBlockedRequest(url, "Rate limit exceeded");
                throw new NetworkSandboxException("Rate limit exceeded for tenant: " + tenantId);
            }

            // 3. 更新统计
            requestCount.incrementAndGet();
            String host = currentUri.getHost();
            hostRequestCount.computeIfAbsent(host, k -> new AtomicInteger(0)).incrementAndGet();

            // 4. 记录审计日志
            logRequest(method, url);

            // 5. 构建请求（重定向时可能需要改变 URI）
            HttpRequest currentRequest = buildRequest(method, currentUri, request);

            // 6. 执行请求
            HttpResponse<String> response;
            try {
                response = httpClient.send(currentRequest, HttpResponse.BodyHandlers.ofString());
            } catch (IOException e) {
                throw new NetworkSandboxException("Network error: " + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new NetworkSandboxException("Request interrupted", e);
            }

            // 7. S1-4b: 检查是否为重定向，手动处理
            if (REDIRECT_STATUS_CODES.contains(response.statusCode()) && policy.isFollowRedirects()) {
                if (redirectCount >= MAX_REDIRECTS) {
                    blockedCount.incrementAndGet();
                    logBlockedRequest(url, "Max redirects exceeded");
                    throw new NetworkSandboxException("Max redirects (" + MAX_REDIRECTS + ") exceeded");
                }

                // 从 Location 头解析重定向目标（对齐 Python redirect_target_from_response）
                String locationHeader = response.headers().firstValue("Location").orElse(null);
                if (locationHeader == null || locationHeader.isBlank()) {
                    // 有重定向状态码但没有 Location 头，直接返回响应
                    logResponse(method, url, response);
                    return response;
                }

                // 解析下一跳 URL（等价于 Python urljoin）
                URI redirectUri = resolveRedirect(currentUri, locationHeader);

                // 协议安全检查：只允许 http/https 重定向
                String redirectScheme = redirectUri.getScheme();
                if (redirectScheme == null || 
                    (!redirectScheme.equalsIgnoreCase("http") && !redirectScheme.equalsIgnoreCase("https"))) {
                    blockedCount.incrementAndGet();
                    logBlockedRequest(redirectUri.toString(), "Redirect to non-HTTP protocol");
                    throw new NetworkSandboxException(
                        "Redirect to non-HTTP protocol blocked: " + redirectUri);
                }

                // 303 See Other → 改为 GET（对齐 HTTP 规范）
                if (response.statusCode() == 303) {
                    method = "GET";
                }

                redirectCount++;
                currentUri = redirectUri;
                continue; // 跳回循环顶部，对新 URL 再过一遍安全检查
            }

            // 8. 检查响应大小
            String body = response.body();
            if (body != null && body.length() > policy.getMaxResponseBodySize()) {
                throw new NetworkSandboxException(
                    "Response body too large: " + body.length() + " bytes"
                );
            }

            // 9. 记录响应
            logResponse(method, url, response);

            return response;
        }
    }

    /**
     * 解析重定向目标 URI（等价于 Python urljoin）。
     *
     * <p>处理三种 Location 头格式：
     * <ul>
     *   <li>绝对 URL：{@code https://evil.com/path} → 直接用</li>
     *   <li>协议相对：{@code //evil.com/path} → 继承原协议</li>
     *   <li>相对路径：{@code /path} 或 {@code path} → 用 URI.resolve() 解析</li>
     * </ul>
     *
     * @param baseUri 原始请求的 URI
     * @param locationHeader Location 头的值
     * @return 解析后的重定向目标 URI
     */
    static URI resolveRedirect(URI baseUri, String locationHeader) {
        String location = locationHeader.trim();

        // 协议相对 URL: //host/path → 继承原协议
        if (location.startsWith("//")) {
            String scheme = baseUri.getScheme();
            return URI.create(scheme + ":" + location);
        }

        // 尝试直接解析为 URI（覆盖绝对 URL 和相对路径）
        try {
            URI locationUri = URI.create(location);

            // 如果是绝对 URI（有 scheme），直接用
            if (locationUri.getScheme() != null) {
                return locationUri;
            }

            // 相对路径：用 resolve 解析
            return baseUri.resolve(locationUri);
        } catch (IllegalArgumentException e) {
            // URI.create 解析失败，尝试用字符串拼接
            String baseUrl = baseUri.toString();
            // 去掉 query/fragment 部分
            int qIdx = baseUrl.indexOf('?');
            if (qIdx >= 0) baseUrl = baseUrl.substring(0, qIdx);
            int fIdx = baseUrl.indexOf('#');
            if (fIdx >= 0) baseUrl = baseUrl.substring(0, fIdx);

            // 如果 Location 以 / 开头，替换路径
            if (location.startsWith("/")) {
                // 找到 host 部分的结束位置
                int schemeEnd = baseUrl.indexOf("://");
                if (schemeEnd >= 0) {
                    int pathStart = baseUrl.indexOf('/', schemeEnd + 3);
                    if (pathStart >= 0) {
                        return URI.create(baseUrl.substring(0, pathStart) + location);
                    } else {
                        return URI.create(baseUrl + location);
                    }
                }
            }

            // 其他情况，直接 resolve
            return baseUri.resolve(location);
        }
    }

    /**
     * 根据方法 + URI 构建请求（重定向时需要重建请求）
     */
    private HttpRequest buildRequest(String method, URI uri, HttpRequest originalRequest) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(uri)
            .timeout(Duration.ofSeconds(policy.getRequestTimeoutSeconds()));

        // 复制原始请求的 header（除了 Host，由 HttpClient 自动设置）
        originalRequest.headers().map().forEach((key, values) -> {
            if (!key.equalsIgnoreCase("Host")) {
                for (String value : values) {
                    builder.header(key, value);
                }
            }
        });

        switch (method) {
            case "GET":
                builder.GET();
                break;
            case "POST":
                // POST 重定向时通常不发 body（除了 307/308），这里简化处理
                builder.POST(HttpRequest.BodyPublishers.noBody());
                break;
            case "PUT":
                builder.PUT(HttpRequest.BodyPublishers.noBody());
                break;
            case "DELETE":
                builder.DELETE();
                break;
            default:
                builder.GET();
        }

        return builder.build();
    }

    /**
     * 检查 URL 是否允许访问
     */
    public boolean isUrlAllowed(String url) {
        try {
            URI uri = URI.create(url);
            
            // 检查协议
            String protocol = uri.getScheme();
            if (!policy.getAllowedProtocols().contains(protocol.toLowerCase())) {
                return false;
            }

            // 检查端口
            int port = uri.getPort();
            if (port == -1) {
                port = protocol.equals("https") ? 443 : 80;
            }
            if (!policy.getAllowedPorts().contains(port)) {
                return false;
            }

            // 检查主机
            String host = uri.getHost();
            if (host == null) {
                return false;
            }

            return policy.isHostAllowed(host);

        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 记录请求审计日志
     */
    private void logRequest(String method, String url) {
        if (context.getAuditLogger() != null) {
            context.getAuditLogger().logNetworkRequest(
                tenantId,
                method,
                url
            );
        }
    }

    /**
     * 记录响应审计日志
     */
    private void logResponse(String method, String url, HttpResponse<String> response) {
        if (context.getAuditLogger() != null) {
            context.getAuditLogger().logNetworkResponse(
                tenantId,
                url,
                response.statusCode()
            );
        }
    }

    /**
     * 记录被阻止的请求
     */
    private void logBlockedRequest(String url, String reason) {
        if (context.getAuditLogger() != null) {
            context.getAuditLogger().logBlockedNetworkRequest(tenantId, url, reason);
        }
    }

    /**
     * 获取统计信息
     */
    public NetworkStats getStats() {
        return new NetworkStats(
            requestCount.get(),
            blockedCount.get(),
            Map.copyOf(hostRequestCount)
        );
    }

    // ============ 内部类 ============

    /**
     * 速率限制器（滑动窗口）
     */
    private static class RateLimiter {
        private final int maxRequestsPerSecond;
        private final long windowMillis = 1000; // 1秒窗口
        private final Map<Long, AtomicInteger> windowCounts = new ConcurrentHashMap<>();
        private final AtomicInteger totalInCurrentBurst = new AtomicInteger(0);

        RateLimiter(int maxRequestsPerSecond) {
            this.maxRequestsPerSecond = maxRequestsPerSecond;
        }

        synchronized boolean tryAcquire() {
            if (maxRequestsPerSecond <= 0) {
                return true; // 无限制
            }

            if (totalInCurrentBurst.get() >= maxRequestsPerSecond) {
                return false;
            }
            totalInCurrentBurst.incrementAndGet();

            long now = System.currentTimeMillis();
            long currentWindow = now / windowMillis;
            windowCounts.keySet().removeIf(w -> w < currentWindow - 5);
            windowCounts.computeIfAbsent(currentWindow, k -> new AtomicInteger(0)).incrementAndGet();
            return true;
        }
    }

    /**
     * 网络统计信息
     */
    public static class NetworkStats {
        private final int totalRequests;
        private final int blockedRequests;
        private final Map<String, AtomicInteger> hostCounts;

        public NetworkStats(int totalRequests, int blockedRequests, Map<String, AtomicInteger> hostCounts) {
            this.totalRequests = totalRequests;
            this.blockedRequests = blockedRequests;
            this.hostCounts = hostCounts;
        }

        public int getTotalRequests() { return totalRequests; }
        public int getBlockedRequests() { return blockedRequests; }
        public int getAllowedRequests() { return totalRequests - blockedRequests; }
        public double getBlockRate() { 
            return totalRequests > 0 ? (double) blockedRequests / totalRequests : 0; 
        }
        public Map<String, AtomicInteger> getHostCounts() { return hostCounts; }
    }
}
