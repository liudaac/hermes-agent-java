package com.nousresearch.hermes.acp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;

/**
 * Manages ACP sessions lifecycle.
 */
public class AcpSessionManager {
    private static final Logger logger = LoggerFactory.getLogger(AcpSessionManager.class);
    
    private final Map<String, AcpSession> sessions;
    private final ScheduledExecutorService cleanupExecutor;
    private final long sessionTimeoutMs;
    
    public AcpSessionManager() {
        this(30, TimeUnit.MINUTES);
    }
    
    public AcpSessionManager(long timeout, TimeUnit unit) {
        this.sessions = new ConcurrentHashMap<>();
        this.sessionTimeoutMs = unit.toMillis(timeout);
        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor();
        
        // Schedule periodic cleanup
        this.cleanupExecutor.scheduleAtFixedRate(
            this::cleanupExpiredSessions,
            timeout,
            timeout,
            unit
        );
    }
    
    /**
     * Register a new session.
     */
    public void registerSession(AcpSession session) {
        sessions.put(session.getSessionId(), session);
        logger.debug("Registered session: {}", session.getSessionId());
    }
    
    /**
     * Get a session by ID.
     */
    public AcpSession getSession(String sessionId) {
        return sessions.get(sessionId);
    }
    
    /**
     * Remove a session.
     */
    public void removeSession(String sessionId) {
        AcpSession session = sessions.remove(sessionId);
        if (session != null) {
            logger.debug("Removed session: {}", sessionId);
        }
    }
    
    /**
     * Get all active session IDs.
     */
    public Set<String> getActiveSessionIds() {
        return sessions.keySet();
    }
    
    /**
     * Get count of active sessions.
     */
    public int getSessionCount() {
        return sessions.size();
    }
    
    /**
     * Cleanup expired sessions.
     */
    private void cleanupExpiredSessions() {
        long now = System.currentTimeMillis();
        int cleaned = 0;
        
        for (Map.Entry<String, AcpSession> entry : sessions.entrySet()) {
            AcpSession session = entry.getValue();
            // Cleanup logic based on session activity
            if (!session.isActive()) {
                sessions.remove(entry.getKey());
                cleaned++;
            }
        }
        
        if (cleaned > 0) {
            logger.debug("Cleaned up {} expired sessions", cleaned);
        }
    }
    
    /**
     * Shutdown the session manager.
     */
    public void shutdown() {
        cleanupExecutor.shutdown();
        try {
            if (!cleanupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                cleanupExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            cleanupExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        // Clear all sessions
        sessions.clear();
        logger.info("Session manager shutdown");
    }
}
