package com.nousresearch.hermes.acp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ACP Permissions management.
 * Mirrors Python acp_adapter/permissions.py
 */
public class AcpPermissions {
    private static final Logger logger = LoggerFactory.getLogger(AcpPermissions.class);
    
    private final Set<String> validTokens;
    private final Map<String, Set<String>> sessionPermissions;
    private final Map<String, Long> tokenExpiry;
    
    public AcpPermissions() {
        this.validTokens = ConcurrentHashMap.newKeySet();
        this.sessionPermissions = new ConcurrentHashMap<>();
        this.tokenExpiry = new ConcurrentHashMap<>();
    }
    
    /**
     * Validate a token.
     */
    public boolean validateToken(String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        
        // Check if token is in valid set
        if (!validTokens.contains(token)) {
            return false;
        }
        
        // Check if token has expired
        Long expiry = tokenExpiry.get(token);
        if (expiry != null && expiry < System.currentTimeMillis()) {
            validTokens.remove(token);
            tokenExpiry.remove(token);
            return false;
        }
        
        return true;
    }
    
    /**
     * Add a valid token.
     */
    public void addToken(String token) {
        validTokens.add(token);
    }
    
    /**
     * Add a token with expiry.
     */
    public void addToken(String token, long expiryMillis) {
        validTokens.add(token);
        tokenExpiry.put(token, System.currentTimeMillis() + expiryMillis);
    }
    
    /**
     * Remove a token.
     */
    public void removeToken(String token) {
        validTokens.remove(token);
        tokenExpiry.remove(token);
    }
    
    /**
     * Grant a permission to a session.
     */
    public void grantPermission(String sessionId, String permission) {
        sessionPermissions.computeIfAbsent(sessionId, k -> ConcurrentHashMap.newKeySet())
            .add(permission);
    }
    
    /**
     * Revoke a permission from a session.
     */
    public void revokePermission(String sessionId, String permission) {
        Set<String> perms = sessionPermissions.get(sessionId);
        if (perms != null) {
            perms.remove(permission);
        }
    }
    
    /**
     * Check if a session has a permission.
     */
    public boolean hasPermission(String sessionId, String permission) {
        Set<String> perms = sessionPermissions.get(sessionId);
        return perms != null && perms.contains(permission);
    }
    
    /**
     * Get all permissions for a session.
     */
    public Set<String> getPermissions(String sessionId) {
        return new HashSet<>(sessionPermissions.getOrDefault(sessionId, Collections.emptySet()));
    }
    
    /**
     * Clear all permissions for a session.
     */
    public void clearPermissions(String sessionId) {
        sessionPermissions.remove(sessionId);
    }
    
    /**
     * Define permission constants.
     */
    public static class Permissions {
        public static final String TOOLS_READ = "tools:read";
        public static final String TOOLS_WRITE = "tools:write";
        public static final String RESOURCES_READ = "resources:read";
        public static final String RESOURCES_WRITE = "resources:write";
        public static final String PROMPTS_READ = "prompts:read";
        public static final String SESSIONS_READ = "sessions:read";
        public static final String SESSIONS_WRITE = "sessions:write";
        public static final String ADMIN = "admin";
    }
}
