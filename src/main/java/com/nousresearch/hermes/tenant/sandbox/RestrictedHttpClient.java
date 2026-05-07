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
 */
public class RestrictedHttpClient {

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
        
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(this.policy.getConnectTimeoutSeconds()))
            .followRedirects(this.policy.isFollowRedirects() ? HttpClient.Redirect.NORMAL : HttpClient.Redirect.NEVER)
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
     */
    private HttpResponse<String> execute(HttpRequest request) throws NetworkSandboxException {
        String url = request.uri().toString();

        // 1. 检查 URL 是否允许
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
        String host = request.uri().getHost();
        hostRequestCount.computeIfAbsent(host, k -> new AtomicInteger(0)).incrementAndGet();

        // 4. 记录审计日志
        logRequest(request);

        // 5. 执行请求
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            // 6. 检查响应大小
            String body = response.body();
            if (body != null && body.length() > policy.getMaxResponseBodySize()) {
                throw new NetworkSandboxException(
                    "Response body too large: " + body.length() + " bytes"
                );
            }

            // 7. 记录响应
            logResponse(request, response);

            return response;

        } catch (IOException e) {
            throw new NetworkSandboxException("Network error: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NetworkSandboxException("Request interrupted", e);
        }
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
    private void logRequest(HttpRequest request) {
        if (context.getAuditLogger() != null) {
            context.getAuditLogger().logNetworkRequest(
                tenantId,
                request.method(),
                request.uri().toString()
            );
        }
    }

    /**
     * 记录响应审计日志
     */
    private void logResponse(HttpRequest request, HttpResponse<String> response) {
        if (context.getAuditLogger() != null) {
            context.getAuditLogger().logNetworkResponse(
                tenantId,
                request.uri().toString(),
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

        RateLimiter(int maxRequestsPerSecond) {
            this.maxRequestsPerSecond = maxRequestsPerSecond;
        }

        synchronized boolean tryAcquire() {
            if (maxRequestsPerSecond <= 0) {
                return true; // 无限制
            }

            long now = System.currentTimeMillis();
            long currentWindow = now / windowMillis;

            // 清理旧窗口（5秒前）
            windowCounts.keySet().removeIf(w -> w < currentWindow - 5);

            // 获取当前窗口计数
            AtomicInteger count = windowCounts.computeIfAbsent(currentWindow, k -> new AtomicInteger(0));
            
            // 检查是否超过限制
            if (count.get() >= maxRequestsPerSecond) {
                return false;
            }

            count.incrementAndGet();
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
