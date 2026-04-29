# 网络沙箱详细实现方案

## 1. NetworkSandbox 核心实现

```java
package com.nousresearch.hermes.tenant.sandbox;

import com.nousresearch.hermes.tenant.core.TenantContext;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 网络沙箱 - 控制租户的出站网络访问
 */
public class NetworkSandbox {
    
    private final TenantContext context;
    private final NetworkPolicy policy;
    private final RateLimiter rateLimiter;
    private final HttpClient httpClient;
    
    public NetworkSandbox(TenantContext context, NetworkPolicy policy) {
        this.context = context;
        this.policy = policy;
        this.rateLimiter = new RateLimiter(policy.getMaxRequestsPerSecond());
        
        // 创建受限的 HTTP 客户端
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(policy.getConnectTimeoutSeconds()))
            .followRedirects(policy.isFollowRedirects() 
                ? HttpClient.Redirect.NORMAL 
                : HttpClient.Redirect.NEVER)
            .build();
    }
    
    /**
     * 发送 HTTP 请求（带限制检查）
     */
    public HttpResponse<String> execute(HttpRequest request) 
            throws NetworkSandboxException {
        
        URI uri = request.uri();
        
        // 1. 检查协议是否允许
        if (!isProtocolAllowed(uri.getScheme())) {
            throw new NetworkSandboxException(
                "Protocol not allowed: " + uri.getScheme());
        }
        
        // 2. 检查主机是否允许
        if (!isHostAllowed(uri.getHost())) {
            throw new NetworkSandboxException(
                "Host not allowed: " + uri.getHost());
        }
        
        // 3. 检查端口是否允许
        int port = uri.getPort() != -1 ? uri.getPort() : getDefaultPort(uri.getScheme());
        if (!isPortAllowed(port)) {
            throw new NetworkSandboxException(
                "Port not allowed: " + port);
        }
        
        // 4. 检查速率限制
        if (!rateLimiter.tryAcquire()) {
            throw new NetworkQuotaExceededException(
                "Network rate limit exceeded for tenant: " + context.getTenantId());
        }
        
        // 5. 检查数据大小限制
        long bodySize = request.bodyPublisher()
            .map(p -> p.contentLength())
            .orElse(0L);
        if (bodySize > policy.getMaxRequestBodySize()) {
            throw new NetworkSandboxException(
                "Request body too large: " + bodySize + " bytes");
        }
        
        // 6. 发送请求
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new NetworkSandboxException("Request failed", e);
        }
    }
    
    /**
     * 检查协议是否允许
     */
    private boolean isProtocolAllowed(String protocol) {
        return policy.getAllowedProtocols().contains(protocol.toLowerCase());
    }
    
    /**
     * 检查主机是否允许
     */
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
            return false; // 白名单非空且未匹配
        }
        
        return true;
    }
    
    /**
     * 检查端口是否允许
     */
    private boolean isPortAllowed(int port) {
        return policy.getAllowedPorts().contains(port);
    }
    
    /**
     * 获取默认端口
     */
    private int getDefaultPort(String scheme) {
        return switch (scheme.toLowerCase()) {
            case "http" -> 80;
            case "https" -> 443;
            case "ftp" -> 21;
            case "ssh" -> 22;
            default -> -1;
        };
    }
}

/**
 * 网络策略配置
 */
public class NetworkPolicy {
    
    private Set<String> allowedProtocols = Set.of("http", "https");
    private Set<Integer> allowedPorts = Set.of(80, 443, 8080, 8443);
    private Set<Pattern> hostWhitelist = ConcurrentHashMap.newKeySet();
    private Set<Pattern> hostBlacklist = ConcurrentHashMap.newKeySet();
    private int maxRequestsPerSecond = 10;
    private long maxRequestBodySize = 1024 * 1024; // 1MB
    private int connectTimeoutSeconds = 10;
    private boolean followRedirects = false;
    
    // Builder
    public static Builder builder() {
        return new Builder();
    }
    
    public static class Builder {
        private NetworkPolicy policy = new NetworkPolicy();
        
        public Builder allowHttp() {
            policy.allowedProtocols.add("http");
            return this;
        }
        
        public Builder allowHttps() {
            policy.allowedProtocols.add("https");
            return this;
        }
        
        public Builder allowHost(String pattern) {
            policy.hostWhitelist.add(Pattern.compile(wildcardToRegex(pattern)));
            return this;
        }
        
        public Builder blockHost(String pattern) {
            policy.hostBlacklist.add(Pattern.compile(wildcardToRegex(pattern)));
            return this;
        }
        
        public Builder maxRequestsPerSecond(int max) {
            policy.maxRequestsPerSecond = max;
            return this;
        }
        
        public Builder maxRequestBodySize(long bytes) {
            policy.maxRequestBodySize = bytes;
            return this;
        }
        
        public Builder connectTimeoutSeconds(int seconds) {
            policy.connectTimeoutSeconds = seconds;
            return this;
        }
        
        public Builder followRedirects(boolean follow) {
            policy.followRedirects = follow;
            return this;
        }
        
        private String wildcardToRegex(String wildcard) {
            return wildcard
                .replace(".", "\\.")
                .replace("*", ".*")
                .replace("?", ".");
        }
        
        public NetworkPolicy build() {
            return policy;
        }
    }
    
    // Getters...
}

/**
 * 速率限制器
 */
class RateLimiter {
    private final int maxRequestsPerSecond;
    private final ConcurrentHashMap<Long, Integer> requestCounts = new ConcurrentHashMap<>();
    
    RateLimiter(int maxRequestsPerSecond) {
        this.maxRequestsPerSecond = maxRequestsPerSecond;
    }
    
    synchronized boolean tryAcquire() {
        long now = System.currentTimeMillis() / 1000;
        
        // 清理旧数据
        requestCounts.keySet().removeIf(t -> t < now - 60);
        
        // 检查当前秒
        int current = requestCounts.getOrDefault(now, 0);
        if (current >= maxRequestsPerSecond) {
            return false;
        }
        
        requestCounts.put(now, current + 1);
        return true;
    }
}

/**
 * 网络沙箱异常
 */
public class NetworkSandboxException extends RuntimeException {
    public NetworkSandboxException(String message) {
        super(message);
    }
    
    public NetworkSandboxException(String message, Throwable cause) {
        super(message, cause);
    }
}

public class NetworkQuotaExceededException extends NetworkSandboxException {
    public NetworkQuotaExceededException(String message) {
        super(message);
    }
}
```

## 2. 使用示例

```java
// 配置网络策略
NetworkPolicy policy = NetworkPolicy.builder()
    .allowHttp()
    .allowHttps()
    .allowHost("api.github.com")
    .allowHost("*.openai.com")
    .allowHost("registry.npmjs.org")
    .blockHost("*.internal.company.com")
    .blockHost("localhost")
    .blockHost("127.0.0.*")
    .blockHost("10.*.*.*")
    .blockHost("192.168.*.*")
    .maxRequestsPerSecond(10)
    .maxRequestBodySize(1024 * 1024) // 1MB
    .connectTimeoutSeconds(10)
    .followRedirects(false)
    .build();

// 创建沙箱
NetworkSandbox sandbox = new NetworkSandbox(tenantContext, policy);

// 发送请求
HttpRequest request = HttpRequest.newBuilder()
    .uri(URI.create("https://api.github.com/users/octocat"))
    .GET()
    .build();

HttpResponse<String> response = sandbox.execute(request);
```

## 3. 集成到现有系统

```java
// 修改 TenantSecurityPolicy，添加网络策略
public class TenantSecurityPolicy {
    private boolean allowNetwork;
    private NetworkPolicy networkPolicy; // 新增
    
    // ...
}

// 在 TenantContext 中添加网络沙箱
public class TenantContext {
    private final NetworkSandbox networkSandbox;
    
    public TenantContext(...) {
        // ...
        this.networkSandbox = new NetworkSandbox(
            this,
            config.getSecurityPolicy().getNetworkPolicy() != null
                ? config.getSecurityPolicy().getNetworkPolicy()
                : NetworkPolicy.defaultPolicy()
        );
    }
    
    public HttpResponse<String> httpRequest(HttpRequest request) {
        return networkSandbox.execute(request);
    }
}
```
