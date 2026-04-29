package com.nousresearch.hermes.tenant.session;

import com.nousresearch.hermes.tenant.core.TenantContext;
import com.nousresearch.hermes.tenant.persistence.TenantStateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 分布式会话管理器
 * 
 * 支持多节点环境下的会话管理：
 * - 会话状态持久化到数据库
 * - 会话在节点间迁移
 * - 会话过期检查
 * - 心跳保活
 */
public class DistributedSessionManager {

    private static final Logger logger = LoggerFactory.getLogger(DistributedSessionManager.class);
    
    private final TenantContext context;
    private final TenantStateRepository repository;
    private final String nodeId;
    
    // 本地会话缓存
    private final ConcurrentHashMap<String, SessionHolder> localSessions = new ConcurrentHashMap<>();
    
    // 会话过期检查调度器
    private final ScheduledExecutorService scheduler;
    
    // 心跳间隔
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(30);
    private static final Duration SESSION_TIMEOUT = Duration.ofMinutes(30);
    private static final Duration ABSOLUTE_TIMEOUT = Duration.ofHours(8);
    
    public DistributedSessionManager(TenantContext context, 
                                     TenantStateRepository repository,
                                     String nodeId) {
        this.context = context;
        this.repository = repository;
        this.nodeId = nodeId;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "session-manager-" + context.getTenantId());
            t.setDaemon(true);
            return t;
        });
        
        startSessionCleanupTask();
    }
    
    /**
     * 创建新会话
     */
    public CompletableFuture<Session> createSession(String sessionId, Map<String, Object> metadata) {
        return CompletableFuture.supplyAsync(() -> {
            Session session = new Session(
                sessionId,
                context.getTenantId(),
                nodeId,
                Instant.now(),
                metadata
            );
            
            // 保存到本地缓存
            localSessions.put(sessionId, new SessionHolder(session, Instant.now()));
            
            // 持久化到数据库
            persistSession(session);
            
            logger.debug("Created session {} for tenant: {}", sessionId, context.getTenantId());
            return session;
        });
    }
    
    /**
     * 获取会话（优先本地，否则从数据库加载）
     */
    public CompletableFuture<Optional<Session>> getSession(String sessionId) {
        return CompletableFuture.supplyAsync(() -> {
            // 1. 检查本地缓存
            SessionHolder holder = localSessions.get(sessionId);
            if (holder != null) {
                // 更新最后访问时间
                holder.updateLastAccess();
                return Optional.of(holder.session);
            }
            
            // 2. 从数据库加载
            try {
                Optional<TenantStateRepository.SessionState> stateOpt = 
                    repository.loadSession(context.getTenantId(), sessionId).get();
                
                if (stateOpt.isPresent()) {
                    TenantStateRepository.SessionState state = stateOpt.get();
                    
                    // 检查是否过期
                    if (isSessionExpired(state)) {
                        logger.debug("Session {} has expired", sessionId);
                        repository.deleteSession(context.getTenantId(), sessionId).get();
                        return Optional.empty();
                    }
                    
                    // 迁移到当前节点
                    Session session = migrateSession(state);
                    localSessions.put(sessionId, new SessionHolder(session, Instant.now()));
                    
                    logger.debug("Migrated session {} to node: {}", sessionId, nodeId);
                    return Optional.of(session);
                }
                
                return Optional.empty();
                
            } catch (Exception e) {
                logger.error("Failed to load session: {}", sessionId, e);
                return Optional.empty();
            }
        });
    }
    
    /**
     * 更新会话状态
     */
    public CompletableFuture<Void> updateSession(String sessionId, Map<String, Object> updates) {
        return CompletableFuture.runAsync(() -> {
            SessionHolder holder = localSessions.get(sessionId);
            if (holder != null) {
                holder.session.updateMetadata(updates);
                holder.updateLastAccess();
                persistSession(holder.session);
            }
        });
    }
    
    /**
     * 释放会话（保留在数据库中）
     */
    public CompletableFuture<Void> releaseSession(String sessionId) {
        return CompletableFuture.runAsync(() -> {
            SessionHolder holder = localSessions.remove(sessionId);
            if (holder != null) {
                // 持久化最终状态
                persistSession(holder.session);
                logger.debug("Released session {} from node: {}", sessionId, nodeId);
            }
        });
    }
    
    /**
     * 销毁会话（完全删除）
     */
    public CompletableFuture<Void> destroySession(String sessionId) {
        return CompletableFuture.runAsync(() -> {
            localSessions.remove(sessionId);
            try {
                repository.deleteSession(context.getTenantId(), sessionId).get();
                logger.debug("Destroyed session: {}", sessionId);
            } catch (Exception e) {
                logger.error("Failed to destroy session: {}", sessionId, e);
            }
        });
    }
    
    /**
     * 获取所有活跃会话
     */
    public Set<String> getActiveSessionIds() {
        return Set.copyOf(localSessions.keySet());
    }
    
    /**
     * 获取活跃会话数
     */
    public int getActiveSessionCount() {
        return localSessions.size();
    }
    
    /**
     * 关闭管理器
     */
    public void shutdown() {
        logger.info("Shutting down session manager for tenant: {}", context.getTenantId());
        
        scheduler.shutdown();
        
        // 持久化所有本地会话
        for (Map.Entry<String, SessionHolder> entry : localSessions.entrySet()) {
            try {
                persistSession(entry.getValue().session);
            } catch (Exception e) {
                logger.error("Failed to persist session during shutdown: {}", entry.getKey(), e);
            }
        }
        
        try {
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    
    // ============ 内部方法 ============
    
    private void persistSession(Session session) {
        try {
            TenantStateRepository.SessionState state = new TenantStateRepository.SessionState(
                session.getSessionId(),
                session.getTenantId(),
                session.getCreatedAt(),
                session.getLastActivity(),
                session.getMetadata(),
                serializeSession(session)
            );
            
            repository.saveSession(context.getTenantId(), session.getSessionId(), state).get();
            
        } catch (Exception e) {
            logger.error("Failed to persist session: {}", session.getSessionId(), e);
        }
    }
    
    private byte[] serializeSession(Session session) {
        // TODO: 实现会话序列化
        return new byte[0];
    }
    
    private Session migrateSession(TenantStateRepository.SessionState state) {
        return new Session(
            state.sessionId(),
            state.tenantId(),
            nodeId,  // 更新到当前节点
            state.createdAt(),
            state.metadata()
        );
    }
    
    private boolean isSessionExpired(TenantStateRepository.SessionState state) {
        Instant now = Instant.now();
        Instant lastActivity = state.lastActivity();
        Instant createdAt = state.createdAt();
        
        // 空闲超时检查
        if (Duration.between(lastActivity, now).compareTo(SESSION_TIMEOUT) > 0) {
            return true;
        }
        
        // 绝对超时检查
        if (Duration.between(createdAt, now).compareTo(ABSOLUTE_TIMEOUT) > 0) {
            return true;
        }
        
        return false;
    }
    
    private void startSessionCleanupTask() {
        scheduler.scheduleAtFixedRate(
            this::cleanupExpiredSessions,
            HEARTBEAT_INTERVAL.getSeconds(),
            HEARTBEAT_INTERVAL.getSeconds(),
            TimeUnit.SECONDS
        );
    }
    
    private void cleanupExpiredSessions() {
        Instant now = Instant.now();
        
        for (Map.Entry<String, SessionHolder> entry : localSessions.entrySet()) {
            SessionHolder holder = entry.getValue();
            
            // 检查空闲超时
            if (Duration.between(holder.lastAccess, now).compareTo(SESSION_TIMEOUT) > 0) {
                logger.debug("Session {} idle timeout, releasing", entry.getKey());
                releaseSession(entry.getKey());
                continue;
            }
            
            // 检查绝对超时
            if (Duration.between(holder.session.getCreatedAt(), now).compareTo(ABSOLUTE_TIMEOUT) > 0) {
                logger.debug("Session {} absolute timeout, destroying", entry.getKey());
                destroySession(entry.getKey());
            }
        }
    }
    
    // ============ 内部类 ============
    
    /**
     * 会话持有者
     */
    private static class SessionHolder {
        final Session session;
        volatile Instant lastAccess;
        
        SessionHolder(Session session, Instant lastAccess) {
            this.session = session;
            this.lastAccess = lastAccess;
        }
        
        void updateLastAccess() {
            this.lastAccess = Instant.now();
            this.session.updateLastActivity();
        }
    }
    
    /**
     * 会话
     */
    public static class Session {
        private final String sessionId;
        private final String tenantId;
        private final String nodeId;
        private final Instant createdAt;
        private volatile Instant lastActivity;
        private final Map<String, Object> metadata;
        private final AtomicBoolean active = new AtomicBoolean(true);
        
        Session(String sessionId, String tenantId, String nodeId, 
                Instant createdAt, Map<String, Object> metadata) {
            this.sessionId = sessionId;
            this.tenantId = tenantId;
            this.nodeId = nodeId;
            this.createdAt = createdAt;
            this.lastActivity = createdAt;
            this.metadata = new ConcurrentHashMap<>(metadata);
        }
        
        public String getSessionId() { return sessionId; }
        public String getTenantId() { return tenantId; }
        public String getNodeId() { return nodeId; }
        public Instant getCreatedAt() { return createdAt; }
        public Instant getLastActivity() { return lastActivity; }
        public Map<String, Object> getMetadata() { return Map.copyOf(metadata); }
        public boolean isActive() { return active.get(); }
        
        void updateLastActivity() {
            this.lastActivity = Instant.now();
        }
        
        void updateMetadata(Map<String, Object> updates) {
            metadata.putAll(updates);
        }
        
        void deactivate() {
            active.set(false);
        }
    }
}
