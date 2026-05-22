package com.nousresearch.hermes.tenant.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 租户会话管理器 - 修复版
 * 
 * 修复内容：
 * - 实现 getActiveSessionCount() 返回实际计数
 * - 添加 createSession() 方法
 * - 添加 getSession() 方法
 * - 自动维护活跃会话计数
 */
public class TenantSessionManager {
    private static final Logger logger = LoggerFactory.getLogger(TenantSessionManager.class);
    
    private final Path sessionsDir;
    private final TenantContext context;
    
    // 活跃会话计数器
    private final AtomicInteger activeSessionCount = new AtomicInteger(0);
    
    // 会话缓存
    private final Map<String, Session> sessionCache = new ConcurrentHashMap<>();
    
    public TenantSessionManager(Path sessionsDir, TenantContext context) {
        this.sessionsDir = sessionsDir;
        this.context = context;
        
        try {
            Files.createDirectories(sessionsDir);
        } catch (IOException e) {
            logger.error("Failed to create sessions directory", e);
        }
    }
    
    public static TenantSessionManager load(Path sessionsDir, TenantContext context) {
        return new TenantSessionManager(sessionsDir, context);
    }
    
    /**
     * 创建新会话
     */
    public Session createSession(String sessionId) {
        Session session = new Session(sessionId);
        sessionCache.put(sessionId, session);
        
        int count = activeSessionCount.incrementAndGet();
        logger.debug("Created session: {} (active: {})", sessionId, count);
        
        return session;
    }
    
    /**
     * 获取或创建会话
     */
    public Session getOrCreateSession(String sessionId) {
        return sessionCache.computeIfAbsent(sessionId, this::createSession);
    }
    
    /**
     * 获取会话
     */
    public Session getSession(String sessionId) {
        return sessionCache.get(sessionId);
    }
    
    /**
     * 删除会话
     */
    public void deleteSession(String sessionId) throws IOException {
        Session removed = sessionCache.remove(sessionId);
        if (removed != null) {
            activeSessionCount.decrementAndGet();
        }
        
        Path sessionFile = sessionsDir.resolve(sessionId + ".json");
        if (Files.exists(sessionFile)) {
            Files.delete(sessionFile);
            logger.info("Deleted session: {}", sessionId);
        }
    }

    /**
     * 列出所有会话ID
     */
    public java.util.List<String> listSessions() throws IOException {
        java.util.List<String> sessions = new java.util.ArrayList<>();
        if (Files.exists(sessionsDir)) {
            try (var stream = Files.list(sessionsDir)) {
                stream.filter(p -> p.toString().endsWith(".json"))
                      .forEach(p -> sessions.add(p.getFileName().toString().replace(".json", "")));
            }
        }
        return sessions;
    }

    /**
     * 获取活跃会话数量 - 已修复
     */
    public int getActiveSessionCount() {
        return activeSessionCount.get();
    }
    
    /**
     * 持久化所有会话
     */
    public void persistAll() {
        // 持久化逻辑
        logger.debug("Persisting {} sessions", sessionCache.size());
    }
    
    /**
     * 会话类
     */
    public static class Session {
        private final String id;
        private final long createdAt;
        private final java.util.List<Message> messages = new java.util.ArrayList<>();
        
        public Session(String id) {
            this.id = id;
            this.createdAt = System.currentTimeMillis();
        }
        
        public String getId() { return id; }
        public long getCreatedAt() { return createdAt; }
        public int getMessageCount() { return messages.size(); }
        
        public void addMessage(String role, String content) {
            messages.add(new Message(role, content, System.currentTimeMillis()));
        }
        
        public java.util.List<Message> getMessages() {
            return new java.util.ArrayList<>(messages);
        }
    }
    
    /**
     * 消息类
     */
    public static class Message {
        private final String role;
        private final String content;
        private final long timestamp;
        
        public Message(String role, String content, long timestamp) {
            this.role = role;
            this.content = content;
            this.timestamp = timestamp;
        }
        
        public String getRole() { return role; }
        public String getContent() { return content; }
        public long getTimestamp() { return timestamp; }
    }
}
