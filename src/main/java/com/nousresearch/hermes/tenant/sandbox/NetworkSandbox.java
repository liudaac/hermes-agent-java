package com.nousresearch.hermes.tenant.sandbox;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * 网络沙箱 - 控制租户的出站网络访问
 */
public class NetworkSandbox {

    private final NetworkPolicy policy;
    private final RateLimiter rateLimiter;
    private final HttpClient httpClient;

    public NetworkSandbox(NetworkPolicy policy) {
        this.policy = policy != null ? policy : NetworkPolicy.defaultPolicy();
        this.rateLimiter = new RateLimiter(this.policy.getMaxRequestsPerSecond());
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(this.policy.getConnectTimeoutSeconds()))
            .followRedirects(this.policy.isFollowRedirects()
                ? HttpClient.Redirect.NORMAL
                : HttpClient.Redirect.NEVER)
            .build();
    }

    /**
     * 发送 HTTP GET 请求
     */
    public HttpResponse<String> get(String url) throws NetworkSandboxException {
        return execute(HttpRequest.newBuilder()
            .uri(URI.create(url))
            .GET()
            .build());
    }

    /**
     * 发送 HTTP POST 请求
     */
    public HttpResponse<String> post(String url, String body) throws NetworkSandboxException {
        return execute(HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build());
    }

    /**
     * 发送 HTTP 请求（带限制检查）
     */
    public HttpResponse<String> execute(HttpRequest request) throws NetworkSandboxException {
        URI uri = request.uri();

        // 1. 检查协议
        String scheme = uri.getScheme();
        if (scheme == null || !policy.getAllowedProtocols().contains(scheme.toLowerCase())) {
            throw new NetworkSandboxException("Protocol not allowed: " + scheme);
        }

        // 2. 检查主机
        String host = uri.getHost();
        if (host == null || !isHostAllowed(host)) {
            throw new NetworkSandboxException("Host not allowed: " + host);
        }

        // 3. 检查端口
        int port = uri.getPort() != -1 ? uri.getPort() : getDefaultPort(scheme);
        if (!policy.getAllowedPorts().contains(port)) {
            throw new NetworkSandboxException("Port not allowed: " + port);
        }

        // 4. 速率限制
        if (!rateLimiter.tryAcquire()) {
            throw new NetworkQuotaExceededException("Rate limit exceeded");
        }

        // 5. 发送请求
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            throw new NetworkSandboxException("Request failed: " + e.getMessage(), e);
        }
    }

    private boolean isHostAllowed(String host) {
        // 先检查黑名单
        for (Pattern pattern : policy.getHostBlacklist()) {
            if (pattern.matcher(host).matches()) {
                return false;
            }
        }

        // 再检查白名单（如果配置了）
        if (!policy.getHostWhitelist().isEmpty()) {
            for (Pattern pattern : policy.getHostWhitelist()) {
                if (pattern.matcher(host).matches()) {
                    return true;
                }
            }
            return false;
        }

        return true;
    }

    private int getDefaultPort(String scheme) {
        return switch (scheme.toLowerCase()) {
            case "http" -> 80;
            case "https" -> 443;
            case "ftp" -> 21;
            default -> -1;
        };
    }
}
