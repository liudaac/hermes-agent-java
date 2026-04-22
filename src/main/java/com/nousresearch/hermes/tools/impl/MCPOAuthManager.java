package com.nousresearch.hermes.tools.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Central manager for per-server MCP OAuth state.
 * Mirrors Python tools/mcp_oauth_manager.py
 *
 * Manages OAuth tokens with:
 * - Cross-process token reload via mtime-based disk watch
 * - 401 deduplication via in-flight futures
 * - Reconnect signaling for long-lived MCP sessions
 */
public class MCPOAuthManager {
    private static final Logger logger = LoggerFactory.getLogger(MCPOAuthManager.class);

    private static final long TOKEN_REFRESH_BUFFER_MS = 60000; // 1 minute buffer

    // Singleton instance
    private static final MCPOAuthManager INSTANCE = new MCPOAuthManager();

    // Per-server OAuth entries
    private final Map<String, OAuthEntry> entries = new ConcurrentHashMap<>();

    // In-flight 401 recovery futures for deduplication
    private final Map<String, CompletableFuture<Boolean>> pending401 = new ConcurrentHashMap<>();

    private MCPOAuthManager() {}

    public static MCPOAuthManager getInstance() {
        return INSTANCE;
    }

    /**
     * Get or create OAuth entry for a server.
     *
     * @param serverName Server name
     * @param serverUrl Server URL
     * @param tokenPath Path to token file
     * @return OAuth entry
     */
    public OAuthEntry getEntry(String serverName, String serverUrl, String tokenPath) {
        return entries.computeIfAbsent(serverName, k -> new OAuthEntry(serverName, serverUrl, tokenPath));
    }

    /**
     * Remove entry for a server.
     *
     * @param serverName Server name
     */
    public void removeEntry(String serverName) {
        entries.remove(serverName);
    }

    /**
     * Clear all entries.
     */
    public void clear() {
        entries.clear();
        pending401.clear();
    }

    /**
     * Check if token needs refresh (based on expiry or disk change).
     *
     * @param entry OAuth entry
     * @return true if refresh needed
     */
    public boolean needsRefresh(OAuthEntry entry) {
        // Check token expiry
        if (entry.getAccessTokenExpiry() > 0) {
            long expiryWithBuffer = entry.getAccessTokenExpiry() - TOKEN_REFRESH_BUFFER_MS;
            if (System.currentTimeMillis() >= expiryWithBuffer) {
                return true;
            }
        }

        // Check if disk file changed
        if (entry.getTokenPath() != null) {
            try {
                Path path = Paths.get(entry.getTokenPath());
                if (Files.exists(path)) {
                    FileTime mtime = Files.getLastModifiedTime(path);
                    long currentMtime = mtime.toMillis();
                    if (currentMtime > entry.getLastMtime()) {
                        return true;
                    }
                }
            } catch (IOException e) {
                logger.debug("Error checking token file mtime", e);
            }
        }

        return false;
    }

    /**
     * Handle 401 error with deduplication.
     *
     * @param serverName Server name
     * @param accessToken Failed access token
     * @param recovery Recovery function
     * @return Future with recovery result
     */
    public CompletableFuture<Boolean> handle401(String serverName, String accessToken, java.util.function.Supplier<Boolean> recovery) {
        String key = serverName + ":" + accessToken;

        // Check if there's already a pending recovery
        CompletableFuture<Boolean> pending = pending401.get(key);
        if (pending != null && !pending.isDone()) {
            logger.debug("Deduplicating 401 recovery for {}", serverName);
            return pending;
        }

        // Create new recovery future
        CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() -> {
            try {
                return recovery.get();
            } finally {
                pending401.remove(key);
            }
        });

        pending401.put(key, future);
        return future;
    }

    /**
     * OAuth entry for a server.
     */
    public static class OAuthEntry {
        private final String serverName;
        private final String serverUrl;
        private final String tokenPath;

        private String accessToken;
        private String refreshToken;
        private long accessTokenExpiry;
        private long lastMtime;

        public OAuthEntry(String serverName, String serverUrl, String tokenPath) {
            this.serverName = serverName;
            this.serverUrl = serverUrl;
            this.tokenPath = tokenPath;
            this.lastMtime = 0;
        }

        public String getServerName() { return serverName; }
        public String getServerUrl() { return serverUrl; }
        public String getTokenPath() { return tokenPath; }

        public String getAccessToken() { return accessToken; }
        public void setAccessToken(String token) { this.accessToken = token; }

        public String getRefreshToken() { return refreshToken; }
        public void setRefreshToken(String token) { this.refreshToken = token; }

        public long getAccessTokenExpiry() { return accessTokenExpiry; }
        public void setAccessTokenExpiry(long expiry) { this.accessTokenExpiry = expiry; }

        public long getLastMtime() { return lastMtime; }
        public void setLastMtime(long mtime) { this.lastMtime = mtime; }

        /**
         * Load tokens from disk.
         */
        public boolean loadFromDisk() {
            if (tokenPath == null) {
                return false;
            }

            try {
                Path path = Paths.get(tokenPath);
                if (!Files.exists(path)) {
                    return false;
                }

                String content = Files.readString(path);
                // Parse token file (JSON or key=value format)
                if (content.trim().startsWith("{")) {
                    // JSON format
                    com.alibaba.fastjson2.JSONObject json = com.alibaba.fastjson2.JSON.parseObject(content);
                    this.accessToken = json.getString("access_token");
                    this.refreshToken = json.getString("refresh_token");
                    this.accessTokenExpiry = json.getLongValue("expires_at");
                } else {
                    // Key=value format
                    for (String line : content.split("\n")) {
                        line = line.trim();
                        if (line.startsWith("access_token=")) {
                            this.accessToken = line.substring("access_token=".length());
                        } else if (line.startsWith("refresh_token=")) {
                            this.refreshToken = line.substring("refresh_token=".length());
                        } else if (line.startsWith("expires_at=")) {
                            this.accessTokenExpiry = Long.parseLong(line.substring("expires_at=".length()));
                        }
                    }
                }

                // Update mtime
                FileTime mtime = Files.getLastModifiedTime(path);
                this.lastMtime = mtime.toMillis();

                logger.debug("Loaded tokens for {} from disk", serverName);
                return true;

            } catch (Exception e) {
                logger.error("Error loading tokens from disk for {}", serverName, e);
                return false;
            }
        }

        /**
         * Save tokens to disk.
         */
        public boolean saveToDisk() {
            if (tokenPath == null) {
                return false;
            }

            try {
                com.alibaba.fastjson2.JSONObject json = new com.alibaba.fastjson2.JSONObject();
                json.put("access_token", accessToken);
                json.put("refresh_token", refreshToken);
                json.put("expires_at", accessTokenExpiry);

                Files.writeString(Paths.get(tokenPath), json.toJSONString());

                // Update mtime
                FileTime mtime = Files.getLastModifiedTime(Paths.get(tokenPath));
                this.lastMtime = mtime.toMillis();

                logger.debug("Saved tokens for {} to disk", serverName);
                return true;

            } catch (Exception e) {
                logger.error("Error saving tokens to disk for {}", serverName, e);
                return false;
            }
        }

        /**
         * Check if tokens are valid.
         */
        public boolean hasValidTokens() {
            return accessToken != null && !accessToken.isEmpty();
        }
    }
}
