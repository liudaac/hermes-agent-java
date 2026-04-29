package com.nousresearch.hermes.tenant.sandbox;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 网络访问策略配置
 * 
 * 定义租户的网络访问权限：
 * - 协议限制（http/https）
 * - 主机白名单/黑名单（支持通配符）
 * - 端口限制
 * - 速率限制
 * - 请求/响应大小限制
 */
public class NetworkPolicy {

    // 默认配置
    private static final NetworkPolicy DEFAULT_POLICY = builder()
        .allowProtocol("http")
        .allowProtocol("https")
        .allowPort(80)
        .allowPort(443)
        .blockHost("localhost")
        .blockHost("127.0.0.*")
        .blockHost("10.*.*.*")
        .blockHost("172.16.*.*")
        .blockHost("172.17.*.*")
        .blockHost("172.18.*.*")
        .blockHost("172.19.*.*")
        .blockHost("172.20.*.*")
        .blockHost("172.21.*.*")
        .blockHost("172.22.*.*")
        .blockHost("172.23.*.*")
        .blockHost("172.24.*.*")
        .blockHost("172.25.*.*")
        .blockHost("172.26.*.*")
        .blockHost("172.27.*.*")
        .blockHost("172.28.*.*")
        .blockHost("172.29.*.*")
        .blockHost("172.30.*.*")
        .blockHost("172.31.*.*")
        .blockHost("192.168.*.*")
        .blockHost("169.254.*.*")  // Link-local
        .blockHost("0.0.0.0")
        .blockHost("::1")  // IPv6 localhost
        .blockHost("fe80::*")  // IPv6 link-local
        .maxRequestsPerSecond(10)
        .connectTimeoutSeconds(10)
        .requestTimeoutSeconds(30)
        .maxRequestBodySize(10 * 1024 * 1024)   // 10MB
        .maxResponseBodySize(50 * 1024 * 1024)  // 50MB
        .followRedirects(false)
        .build();

    private final Set<String> allowedProtocols;
    private final Set<Integer> allowedPorts;
    private final Set<Pattern> hostWhitelist;
    private final Set<Pattern> hostBlacklist;
    private final int maxRequestsPerSecond;
    private final int connectTimeoutSeconds;
    private final int requestTimeoutSeconds;
    private final long maxRequestBodySize;
    private final long maxResponseBodySize;
    private final boolean followRedirects;

    private NetworkPolicy(Builder builder) {
        this.allowedProtocols = Set.copyOf(builder.allowedProtocols);
        this.allowedPorts = Set.copyOf(builder.allowedPorts);
        this.hostWhitelist = Set.copyOf(builder.hostWhitelist);
        this.hostBlacklist = Set.copyOf(builder.hostBlacklist);
        this.maxRequestsPerSecond = builder.maxRequestsPerSecond;
        this.connectTimeoutSeconds = builder.connectTimeoutSeconds;
        this.requestTimeoutSeconds = builder.requestTimeoutSeconds;
        this.maxRequestBodySize = builder.maxRequestBodySize;
        this.maxResponseBodySize = builder.maxResponseBodySize;
        this.followRedirects = builder.followRedirects;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static NetworkPolicy defaultPolicy() {
        return DEFAULT_POLICY;
    }

    // ============ 业务方法 ============

    /**
     * 检查主机是否允许访问
     */
    public boolean isHostAllowed(String host) {
        // 先检查黑名单（优先级更高）
        for (Pattern pattern : hostBlacklist) {
            if (pattern.matcher(host).matches()) {
                return false;
            }
        }

        // 再检查白名单
        if (!hostWhitelist.isEmpty()) {
            for (Pattern pattern : hostWhitelist) {
                if (pattern.matcher(host).matches()) {
                    return true;
                }
            }
            return false;  // 白名单非空但未匹配
        }

        return true;  // 无白名单时默认允许
    }

    // ============ Getters ============

    public Set<String> getAllowedProtocols() {
        return allowedProtocols;
    }

    public Set<Integer> getAllowedPorts() {
        return allowedPorts;
    }

    public Set<Pattern> getHostWhitelist() {
        return hostWhitelist;
    }

    public Set<Pattern> getHostBlacklist() {
        return hostBlacklist;
    }

    public int getMaxRequestsPerSecond() {
        return maxRequestsPerSecond;
    }

    public int getConnectTimeoutSeconds() {
        return connectTimeoutSeconds;
    }

    public int getRequestTimeoutSeconds() {
        return requestTimeoutSeconds;
    }

    public long getMaxRequestBodySize() {
        return maxRequestBodySize;
    }

    public long getMaxResponseBodySize() {
        return maxResponseBodySize;
    }

    public boolean isFollowRedirects() {
        return followRedirects;
    }

    // ============ Builder ============

    public static class Builder {
        private final Set<String> allowedProtocols = new HashSet<>();
        private final Set<Integer> allowedPorts = new HashSet<>();
        private final Set<Pattern> hostWhitelist = new HashSet<>();
        private final Set<Pattern> hostBlacklist = new HashSet<>();
        private int maxRequestsPerSecond = 0;  // 0 = unlimited
        private int connectTimeoutSeconds = 10;
        private int requestTimeoutSeconds = 30;
        private long maxRequestBodySize = 10 * 1024 * 1024;   // 10MB
        private long maxResponseBodySize = 50 * 1024 * 1024;  // 50MB
        private boolean followRedirects = false;

        public Builder allowProtocol(String protocol) {
            this.allowedProtocols.add(protocol.toLowerCase());
            return this;
        }

        public Builder allowPort(int port) {
            this.allowedPorts.add(port);
            return this;
        }

        public Builder allowHost(String hostPattern) {
            this.hostWhitelist.add(wildcardToPattern(hostPattern));
            return this;
        }

        public Builder blockHost(String hostPattern) {
            this.hostBlacklist.add(wildcardToPattern(hostPattern));
            return this;
        }

        public Builder maxRequestsPerSecond(int max) {
            this.maxRequestsPerSecond = max;
            return this;
        }

        public Builder connectTimeoutSeconds(int seconds) {
            this.connectTimeoutSeconds = seconds;
            return this;
        }

        public Builder requestTimeoutSeconds(int seconds) {
            this.requestTimeoutSeconds = seconds;
            return this;
        }

        public Builder maxRequestBodySize(long bytes) {
            this.maxRequestBodySize = bytes;
            return this;
        }

        public Builder maxResponseBodySize(long bytes) {
            this.maxResponseBodySize = bytes;
            return this;
        }

        public Builder followRedirects(boolean follow) {
            this.followRedirects = follow;
            return this;
        }

        public NetworkPolicy build() {
            return new NetworkPolicy(this);
        }

        /**
         * 将通配符转换为正则表达式
         * *.github.com -> .*\.github\.com
         * api.*.com -> api\..*\.com
         */
        private Pattern wildcardToPattern(String wildcard) {
            StringBuilder regex = new StringBuilder();
            regex.append("^");
            
            for (char c : wildcard.toCharArray()) {
                switch (c) {
                    case '*':
                        regex.append(".*");
                        break;
                    case '.':
                        regex.append("\\.");
                        break;
                    case '?':
                        regex.append(".");
                        break;
                    default:
                        regex.append(Pattern.quote(String.valueOf(c)));
                }
            }
            
            regex.append("$");
            
            try {
                return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE);
            } catch (PatternSyntaxException e) {
                throw new IllegalArgumentException("Invalid host pattern: " + wildcard, e);
            }
        }
    }

    @Override
    public String toString() {
        return "NetworkPolicy{" +
                "protocols=" + allowedProtocols +
                ", ports=" + allowedPorts +
                ", whitelist=" + hostWhitelist.size() +
                ", blacklist=" + hostBlacklist.size() +
                ", maxRPS=" + maxRequestsPerSecond +
                '}';
    }
}
