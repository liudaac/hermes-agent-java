package com.nousresearch.hermes.acp;

import com.alibaba.fastjson2.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * ACP Entry point.
 * Mirrors Python acp_adapter/entry.py
 * Main entry point for starting the ACP server.
 */
public class AcpEntry {
    private static final Logger logger = LoggerFactory.getLogger(AcpEntry.class);
    
    private AcpServer server;
    private final int port;
    private final AcpConfig config;
    
    public AcpEntry() {
        this(8080, new AcpConfig());
    }
    
    public AcpEntry(int port) {
        this(port, new AcpConfig());
    }
    
    public AcpEntry(int port, AcpConfig config) {
        this.port = port;
        this.config = config;
    }
    
    /**
     * Start the ACP server.
     */
    public void start() {
        logger.info("Starting ACP server on port {}", port);
        
        server = new AcpServer(port);
        server.start();
        
        logger.info("ACP server started successfully");
    }
    
    /**
     * Stop the ACP server.
     */
    public void stop() {
        if (server != null) {
            logger.info("Stopping ACP server");
            server.stop();
            server = null;
        }
    }
    
    /**
     * Check if server is running.
     */
    public boolean isRunning() {
        return server != null;
    }
    
    /**
     * Get server port.
     */
    public int getPort() {
        return port;
    }
    
    /**
     * Get configuration.
     */
    public AcpConfig getConfig() {
        return config;
    }
    
    /**
     * ACP Configuration.
     */
    public static class AcpConfig {
        private boolean enableWebSocket = true;
        private boolean enableHttp = true;
        private boolean requireAuth = false;
        private Map<String, String> authTokens = new HashMap<>();
        private int maxSessions = 100;
        private long sessionTimeoutMs = 30 * 60 * 1000; // 30 minutes
        
        public boolean isEnableWebSocket() { return enableWebSocket; }
        public void setEnableWebSocket(boolean enableWebSocket) { this.enableWebSocket = enableWebSocket; }
        
        public boolean isEnableHttp() { return enableHttp; }
        public void setEnableHttp(boolean enableHttp) { this.enableHttp = enableHttp; }
        
        public boolean isRequireAuth() { return requireAuth; }
        public void setRequireAuth(boolean requireAuth) { this.requireAuth = requireAuth; }
        
        public Map<String, String> getAuthTokens() { return authTokens; }
        public void setAuthTokens(Map<String, String> authTokens) { this.authTokens = authTokens; }
        
        public int getMaxSessions() { return maxSessions; }
        public void setMaxSessions(int maxSessions) { this.maxSessions = maxSessions; }
        
        public long getSessionTimeoutMs() { return sessionTimeoutMs; }
        public void setSessionTimeoutMs(long sessionTimeoutMs) { this.sessionTimeoutMs = sessionTimeoutMs; }
        
        /**
         * Create config from JSON.
         */
        public static AcpConfig fromJson(JSONObject json) {
            AcpConfig config = new AcpConfig();
            if (json != null) {
                config.enableWebSocket = json.getBooleanValue("enable_websocket", true);
                config.enableHttp = json.getBooleanValue("enable_http", true);
                config.requireAuth = json.getBooleanValue("require_auth", false);
                config.maxSessions = json.getIntValue("max_sessions", 100);
                config.sessionTimeoutMs = json.getLongValue("session_timeout_ms", 30 * 60 * 1000);
                
                JSONObject tokens = json.getJSONObject("auth_tokens");
                if (tokens != null) {
                    for (String key : tokens.keySet()) {
                        config.authTokens.put(key, tokens.getString(key));
                    }
                }
            }
            return config;
        }
    }
    
    /**
     * Main entry point.
     */
    public static void main(String[] args) {
        int port = 8080;
        
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                logger.error("Invalid port number: {}", args[0]);
                System.exit(1);
            }
        }
        
        AcpEntry entry = new AcpEntry(port);
        
        // Add shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(entry::stop));
        
        entry.start();
    }
}
