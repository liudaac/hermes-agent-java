package com.nousresearch.hermes.tenant.core;

import com.nousresearch.hermes.config.Constants;
import com.nousresearch.hermes.tenant.core.TenantConfig;
import com.nousresearch.hermes.tenant.core.TenantProvisioningRequest;
import com.nousresearch.hermes.tenant.audit.AuditEvent;
import com.nousresearch.hermes.tenant.audit.TenantAuditLogger;
import com.nousresearch.hermes.tenant.quota.TenantQuotaManager;
import com.nousresearch.hermes.tenant.sandbox.*;
import com.nousresearch.hermes.tenant.security.TenantSecurityPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpResponse;
import java.util.List;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 租户上下文 - 完全隔离的运行时环境
 * 
 * 每个租户拥有独立的：
 * - 配置存储
 * - 文件沙箱
 * - 记忆管理
 * - Skill 管理
 * - 会话管理
 * - 工具注册表
 * - 资源配额
 * - 审计日志
 */
public class TenantContext {
    private static final Logger logger = LoggerFactory.getLogger(TenantContext.class);
    
    private final String tenantId;
    private final Path tenantDir;
    private final Instant createdAt;
    private final AtomicReference<State> state = new AtomicReference<>(State.INITIALIZING);
    
    // 生命周期锁
    private final ReentrantReadWriteLock lifecycleLock = new ReentrantReadWriteLock();
    
    // 子组件（延迟初始化）
    private volatile TenantConfig config;
    private volatile TenantFileSandbox fileSandbox;
    private volatile TenantMemoryManager memoryManager;
    private volatile TenantSkillManager skillManager;
    private volatile TenantSessionManager sessionManager;
    private volatile TenantToolRegistry toolRegistry;
    private volatile TenantQuotaManager quotaManager;
    private volatile TenantAuditLogger auditLogger;
    private volatile TenantSecurityPolicy securityPolicy;
    private volatile TenantResourceMonitor resourceMonitor;
    
    // Phase 2: 资源隔离沙箱
    private volatile ProcessSandbox processSandbox;
    private volatile CgroupProcessSandbox cgroupSandbox;
    private volatile NetworkSandbox networkSandbox;
    private volatile RestrictedHttpClient restrictedHttpClient;
    private volatile TenantMemoryPool memoryPool;
    
    // Phase 3: 指标监控
    private volatile com.nousresearch.hermes.tenant.metrics.TenantMetrics metrics;
    
    // 运行时 Agent 管理
    private final ConcurrentHashMap<String, TenantAIAgent> activeAgents = new ConcurrentHashMap<>();
    private volatile Instant lastActivity = Instant.now();
    
    public enum State {
        INITIALIZING,
        ACTIVE,
        SUSPENDED,
        EXPIRED,
        CLEANING_UP,
        DESTROYED
    }
    
    // ============ 构造函数 ============
    
    private TenantContext(String tenantId, Path tenantDir) {
        this.tenantId = tenantId;
        this.tenantDir = tenantDir;
        this.createdAt = Instant.now();
    }
    
    /**
     * 创建新的租户上下文
     */
    public static TenantContext create(String tenantId, TenantProvisioningRequest request) {
        logger.info("Creating tenant context: {}", tenantId);
        
        // 1. 清理和验证租户ID
        String safeTenantId = sanitizeTenantId(tenantId);
        Path tenantDir = Constants.getHermesHome()
            .resolve("tenants")
            .resolve(safeTenantId);
        
        try {
            // 2. 创建目录结构
            createDirectoryStructure(tenantDir);
            
            // 3. 创建上下文实例
            TenantContext context = new TenantContext(safeTenantId, tenantDir);
            
            // 4. 初始化审计日志（最先初始化，用于记录后续操作）
            context.auditLogger = new TenantAuditLogger(tenantDir.resolve("logs"));
            
            // 5. 初始化配额管理器
            context.quotaManager = new TenantQuotaManager(tenantDir, request.getQuota());
            
            // 6. 初始化安全策略
            context.securityPolicy = request.getSecurityPolicy();
            
            // 7. 初始化各隔离组件
            context.initializeComponents(request);
            
            // 8. 设置状态并记录审计
            context.state.set(State.ACTIVE);
            context.auditLogger.log(AuditEvent.TENANT_CREATED, Map.of(
                "tenantId", safeTenantId,
                "quota", request.getQuota(),
                "createdBy", request.getCreatedBy(),
                "createdAt", context.createdAt.toString()
            ));
            
            logger.info("Tenant context created successfully: {}", safeTenantId);
            return context;
            
        } catch (Exception e) {
            logger.error("Failed to create tenant context: {}", tenantId, e);
            // 清理已创建的目录
            cleanupDirectory(tenantDir);
            throw new TenantCreationException("Failed to create tenant: " + e.getMessage(), e);
        }
    }
    
    /**
     * 从现有目录加载租户上下文
     */
    public static TenantContext load(String tenantId) {
        String safeTenantId = sanitizeTenantId(tenantId);
        Path tenantDir = Constants.getHermesHome()
            .resolve("tenants")
            .resolve(safeTenantId);
        
        if (!Files.exists(tenantDir)) {
            throw new TenantNotFoundException("Tenant not found: " + tenantId);
        }
        
        TenantContext context = new TenantContext(safeTenantId, tenantDir);
        
        try {
            // 加载现有配置
            context.loadExistingComponents();
            context.state.set(State.ACTIVE);
            
            logger.info("Tenant context loaded: {}", safeTenantId);
            return context;
            
        } catch (Exception e) {
            logger.error("Failed to load tenant context: {}", tenantId, e);
            throw new TenantLoadException("Failed to load tenant: " + e.getMessage(), e);
        }
    }
    
    // ============ 初始化方法 ============
    
    private void initializeComponents(TenantProvisioningRequest request) {
        lifecycleLock.writeLock().lock();
        try {
            // 文件沙箱
            this.fileSandbox = new TenantFileSandbox(this.tenantId, tenantDir.resolve("workspace"), 
                request.getFileSandboxConfig());
            
            // 配置管理
            this.config = new TenantConfig(tenantDir.resolve("config"), request.getConfig());
            
            // 记忆管理
            this.memoryManager = new TenantMemoryManager(this.tenantId, tenantDir.resolve("memories"));
            
            // Skill 管理
            this.skillManager = new TenantSkillManager(this.tenantId, tenantDir.resolve("skills"), this);
            
            // 会话管理
            this.sessionManager = new TenantSessionManager(tenantDir.resolve("sessions"), this);
            
            // 工具注册表
            this.toolRegistry = new TenantToolRegistry(this);
            
            // 资源监控
            this.resourceMonitor = new TenantResourceMonitor(this);
            
            // Phase 2: 初始化资源隔离沙箱
            initializeResourceSandboxes(request);
            
            // Phase 3: 初始化指标监控
            this.metrics = new com.nousresearch.hermes.tenant.metrics.TenantMetrics(this);
            logger.debug("Initialized metrics for tenant: {}", tenantId);
            
            logger.debug("All tenant components initialized for: {}", tenantId);
            
        } finally {
            lifecycleLock.writeLock().unlock();
        }
    }
    
    /**
     * Phase 2: 初始化资源隔离沙箱
     */
    private void initializeResourceSandboxes(TenantProvisioningRequest request) {
        try {
            // 1. 初始化进程沙箱
            ProcessSandboxConfig processConfig = request.getProcessSandboxConfig();
            if (processConfig == null) {
                processConfig = ProcessSandboxConfig.defaultConfig();
            }
            
            // 优先使用 cgroups（如果系统支持）
            if (CgroupProcessSandbox.isCgroupV2Available()) {
                this.cgroupSandbox = new CgroupProcessSandbox(this, processConfig);
                this.cgroupSandbox.initialize();
                logger.debug("Initialized cgroup sandbox for tenant: {}", tenantId);
            } else {
                this.processSandbox = new ProcessSandbox(this, processConfig);
                logger.debug("Initialized process sandbox for tenant: {}", tenantId);
            }
            
            // 2. 初始化网络沙箱
            NetworkPolicy networkPolicy = request.getNetworkPolicy();
            if (networkPolicy == null && securityPolicy != null) {
                networkPolicy = securityPolicy.getNetworkPolicy();
            }
            if (networkPolicy == null) {
                networkPolicy = NetworkPolicy.defaultPolicy();
            }
            this.networkSandbox = new NetworkSandbox(networkPolicy);
            this.restrictedHttpClient = new RestrictedHttpClient(this, networkPolicy);
            logger.debug("Initialized network sandbox for tenant: {}", tenantId);
            
            // 3. 初始化内存池
            long maxMemory = request.getMaxMemoryBytes();
            if (maxMemory <= 0 && quotaManager != null) {
                maxMemory = quotaManager.getMaxMemoryBytes();
            }
            if (maxMemory <= 0) {
                maxMemory = 256 * 1024 * 1024L; // 默认 256MB
            }
            this.memoryPool = new TenantMemoryPool(tenantId, maxMemory);
            logger.debug("Initialized memory pool for tenant: {} (max: {} MB)", 
                tenantId, maxMemory / (1024 * 1024));
            
            auditLogger.log(AuditEvent.TENANT_CREATED, Map.of(
                "tenantId", tenantId,
                "cgroupEnabled", cgroupSandbox != null,
                "maxMemoryMB", maxMemory / (1024 * 1024)
            ));
            
        } catch (Exception e) {
            logger.error("Failed to initialize resource sandboxes for tenant: {}", tenantId, e);
            throw new TenantCreationException("Failed to initialize resource sandboxes", e);
        }
    }
    
    /**
     * Phase 2: 便捷方法 - 执行命令
     */
    public ProcessResult exec(List<String> command, ProcessOptions options) throws ProcessSandboxException {
        checkState();
        
        if (cgroupSandbox != null) {
            return cgroupSandbox.exec(command, options);
        } else if (processSandbox != null) {
            return processSandbox.exec(command, options);
        } else {
            throw new IllegalStateException("Process sandbox not initialized");
        }
    }
    
    /**
     * Phase 2: 便捷方法 - HTTP GET
     */
    public HttpResponse<String> httpGet(String url) throws NetworkSandboxException {
        checkState();
        return restrictedHttpClient.get(url);
    }
    
    /**
     * Phase 2: 便捷方法 - HTTP POST
     */
    public HttpResponse<String> httpPost(String url, String body) throws NetworkSandboxException {
        checkState();
        return restrictedHttpClient.post(url, body);
    }

    /**
     * Phase 2: 检查网络访问是否被允许
     */
    public boolean isNetworkAllowed(String url) {
        if (restrictedHttpClient == null) {
            return false;
        }
        return restrictedHttpClient.isUrlAllowed(url);
    }

    /**
     * Phase 2: 便捷方法 - 分配内存
     */
    public java.nio.ByteBuffer allocateMemory(int size) throws MemoryQuotaExceededException {
        checkState();
        return memoryPool.allocate(size).getDelegate();
    }
    
    /**
     * Phase 2: 便捷方法 - 释放内存
     * 注意：现在主要依赖 Cleaner 自动释放内存。
     * 如果手动释放，需要保留对 TrackedByteBuffer 的引用并调用其 free() 方法。
     */
    public void freeMemory(java.nio.ByteBuffer buffer) {
        // 由于 allocateMemory 返回的是 delegate，这里无法通过 instanceof 检查
        // 内存会在 TrackedByteBuffer 被 GC 时通过 Cleaner 自动释放
        // 如需手动释放，请使用 allocate() 返回 TrackedByteBuffer 并直接调用 free()
        if (memoryPool != null && buffer != null) {
            memoryPool.free(buffer);
        }
    }
    
    /**
     * Phase 2: 获取内存池统计
     */
    public TenantMemoryPool.MemoryStats getMemoryStats() {
        return memoryPool != null ? memoryPool.getStats() : null;
    }
    
    /**
     * Phase 2: 获取网络统计
     */
    public RestrictedHttpClient.NetworkStats getNetworkStats() {
        return restrictedHttpClient != null ? restrictedHttpClient.getStats() : null;
    }
    
    /**
     * Phase 3: 获取指标监控
     */
    public com.nousresearch.hermes.tenant.metrics.TenantMetrics getMetrics() {
        return metrics;
    }
    
    /**
     * Phase 3: 获取内存池（供 Metrics 使用）
     */
    public TenantMemoryPool getMemoryPool() {
        return memoryPool;
    }
    
    /**
     * Phase 3: 获取活跃 Agent 数量
     */
    public int getActiveAgentCount() {
        return activeAgents.size();
    }
    
    /**
     * Phase 3: 获取活跃会话数量
     */
    public int getActiveSessionCount() {
        return sessionManager != null ? sessionManager.getActiveSessionCount() : 0;
    }

    private void loadExistingComponents() {
        lifecycleLock.writeLock().lock();
        try {
            this.auditLogger = new TenantAuditLogger(tenantDir.resolve("logs"));
            this.quotaManager = TenantQuotaManager.load(tenantDir);
            this.securityPolicy = TenantSecurityPolicy.load(tenantDir.resolve("config"));
            this.fileSandbox = TenantFileSandbox.load(this.tenantId, tenantDir.resolve("workspace"));
            this.config = TenantConfig.load(tenantDir.resolve("config"));
            this.memoryManager = TenantMemoryManager.load(this.tenantId, tenantDir.resolve("memories"));
            this.skillManager = TenantSkillManager.load(this.tenantId, tenantDir.resolve("skills"), this);
            this.sessionManager = TenantSessionManager.load(tenantDir.resolve("sessions"), this);
            this.toolRegistry = new TenantToolRegistry(this);
            this.resourceMonitor = new TenantResourceMonitor(this);
            
        } finally {
            lifecycleLock.writeLock().unlock();
        }
    }
    
    // ============ 生命周期管理 ============
    
    /**
     * 销毁租户
     */
    public void destroy(boolean preserveData) {
        lifecycleLock.writeLock().lock();
        try {
            if (state.get() == State.DESTROYED) {
                return;
            }
            
            state.set(State.CLEANING_UP);
            logger.info("Destroying tenant context: {}", tenantId);
            
            // 1. 停止所有 Agent
            logger.debug("Stopping {} active agents", activeAgents.size());
            for (Map.Entry<String, TenantAIAgent> entry : activeAgents.entrySet()) {
                try {
                    entry.getValue().interrupt();
                    entry.getValue().awaitTermination(30, TimeUnit.SECONDS);
                } catch (Exception e) {
                    logger.warn("Failed to gracefully stop agent {}: {}", entry.getKey(), e.getMessage());
                }
            }
            activeAgents.clear();
            
            // 2. 停止资源监控
            if (resourceMonitor != null) {
                resourceMonitor.shutdown();
            }
            
            // Phase 2: 清理资源沙箱
            if (cgroupSandbox != null) {
                cgroupSandbox.destroy();
            }
            if (memoryPool != null) {
                memoryPool.clear();
            }
            
            // Phase 3: 注销 JMX MBean
            if (metrics != null) {
                metrics.unregister();
            }
            
            // 3. 持久化最终状态
            if (sessionManager != null) {
                sessionManager.persistAll();
            }
            
            // 4. 记录审计
            auditLogger.log(AuditEvent.TENANT_DESTROYED, Map.of(
                "tenantId", tenantId,
                "preserveData", preserveData,
                "destroyedAt", Instant.now().toString()
            ));
            
            // 5. 清理或保留数据
            if (!preserveData) {
                cleanupDirectory(tenantDir);
                logger.info("Tenant data deleted: {}", tenantId);
            }
            
            state.set(State.DESTROYED);
            logger.info("Tenant context destroyed: {}", tenantId);
            
        } finally {
            lifecycleLock.writeLock().unlock();
        }
    }
    
    /**
     * 暂停租户（限制新请求）
     */
    public void suspend(String reason) {
        lifecycleLock.writeLock().lock();
        try {
            state.set(State.SUSPENDED);
            auditLogger.log(AuditEvent.TENANT_SUSPENDED, Map.of(
                "tenantId", tenantId,
                "reason", reason,
                "suspendedAt", Instant.now().toString()
            ));
            logger.info("Tenant suspended: {} - {}", tenantId, reason);
        } finally {
            lifecycleLock.writeLock().unlock();
        }
    }
    
    /**
     * 恢复租户
     */
    public void resume() {
        lifecycleLock.writeLock().lock();
        try {
            state.set(State.ACTIVE);
            auditLogger.log(AuditEvent.TENANT_RESUMED, Map.of(
                "tenantId", tenantId,
                "resumedAt", Instant.now().toString()
            ));
            logger.info("Tenant resumed: {}", tenantId);
        } finally {
            lifecycleLock.writeLock().unlock();
        }
    }
    
    // ============ Agent 管理 ============
    
    /**
     * 创建新的 Agent 实例
     */
    public TenantAIAgent createAgent(String sessionId) {
        checkState();
        
        // 检查配额
        quotaManager.checkConcurrentAgents(activeAgents.size() + 1);
        
        TenantAIAgent agent = new TenantAIAgent(this, sessionId);
        activeAgents.put(sessionId, agent);
        
        updateActivity();
        
        auditLogger.log(AuditEvent.AGENT_CREATED, Map.of(
            "tenantId", tenantId,
            "sessionId", sessionId,
            "activeAgents", activeAgents.size()
        ));
        
        return agent;
    }
    
    /**
     * 获取或创建 Agent
     */
    public TenantAIAgent getOrCreateAgent(String sessionId) {
        return activeAgents.computeIfAbsent(sessionId, id -> createAgent(id));
    }
    
    /**
     * 移除 Agent
     */
    public void removeAgent(String sessionId) {
        TenantAIAgent agent = activeAgents.remove(sessionId);
        if (agent != null) {
            quotaManager.recordAgentDestroyed();
            auditLogger.log(AuditEvent.AGENT_DESTROYED, Map.of(
                "tenantId", tenantId,
                "sessionId", sessionId
            ));
        }
    }
    
    // ============ 状态检查 ============
    
    private void checkState() {
        State current = state.get();
        if (current != State.ACTIVE) {
            throw new IllegalStateException("Tenant is not active: " + current);
        }
    }
    
    public boolean isActive() {
        return state.get() == State.ACTIVE;
    }
    
    public boolean isIdle(long duration, TimeUnit unit) {
        return lastActivity.plus(duration, unit.toChronoUnit()).isBefore(Instant.now());
    }
    
    public void updateActivity() {
        this.lastActivity = Instant.now();
    }
    
    // ============ Getter 方法 ============
    
    public String getTenantId() { return tenantId; }
    public Path getTenantDir() { return tenantDir; }
    public Instant getCreatedAt() { return createdAt; }
    public State getState() { return state.get(); }
    public Instant getLastActivity() { return lastActivity; }
    
    public TenantConfig getConfig() { return config; }
    public TenantFileSandbox getFileSandbox() { return fileSandbox; }
    public TenantMemoryManager getMemoryManager() { return memoryManager; }
    public TenantSkillManager getSkillManager() { return skillManager; }
    public TenantSessionManager getSessionManager() { return sessionManager; }
    public TenantToolRegistry getToolRegistry() { return toolRegistry; }
    public TenantQuotaManager getQuotaManager() { return quotaManager; }
    public TenantAuditLogger getAuditLogger() { return auditLogger; }
    public TenantSecurityPolicy getSecurityPolicy() { return securityPolicy; }
    public TenantResourceMonitor getResourceMonitor() { return resourceMonitor; }
    
    public Map<String, TenantAIAgent> getActiveAgents() { 
        return new ConcurrentHashMap<>(activeAgents); 
    }
    
    // ============ 工具方法 ============
    
    private static String sanitizeTenantId(String tenantId) {
        // 只允许字母数字和下划线
        String sanitized = tenantId.replaceAll("[^a-zA-Z0-9_-]", "_");
        if (sanitized.length() > 64) {
            sanitized = sanitized.substring(0, 64);
        }
        return sanitized.toLowerCase();
    }
    
    private static void createDirectoryStructure(Path tenantDir) throws IOException {
        // 创建租户目录结构
        Files.createDirectories(tenantDir);
        Files.createDirectories(tenantDir.resolve("config"));
        Files.createDirectories(tenantDir.resolve("workspace"));
        Files.createDirectories(tenantDir.resolve("workspace/sessions"));
        Files.createDirectories(tenantDir.resolve("workspace/uploads"));
        Files.createDirectories(tenantDir.resolve("workspace/generated"));
        Files.createDirectories(tenantDir.resolve("workspace/cache"));
        Files.createDirectories(tenantDir.resolve("workspace/temp"));
        Files.createDirectories(tenantDir.resolve("memories"));
        Files.createDirectories(tenantDir.resolve("sessions"));
        Files.createDirectories(tenantDir.resolve("skills/private"));
        Files.createDirectories(tenantDir.resolve("skills/installed"));
        Files.createDirectories(tenantDir.resolve("logs"));
        Files.createDirectories(tenantDir.resolve("state"));
        
        logger.debug("Created tenant directory structure: {}", tenantDir);
    }
    
    private static void cleanupDirectory(Path dir) {
        try {
            if (Files.exists(dir)) {
                deleteRecursive(dir);
            }
        } catch (Exception e) {
            logger.warn("Failed to cleanup directory: {}", dir, e);
        }
    }
    
    private static void deleteRecursive(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (var stream = Files.list(path)) {
                stream.forEach(child -> {
                    try {
                        deleteRecursive(child);
                    } catch (IOException e) {
                        logger.warn("Failed to delete: {}", child, e);
                    }
                });
            }
        }
        Files.delete(path);
    }
    
    // ============ 异常类 ============
    
    public static class TenantCreationException extends RuntimeException {
        public TenantCreationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
    
    public static class TenantLoadException extends RuntimeException {
        public TenantLoadException(String message, Throwable cause) {
            super(message, cause);
        }
    }
    
    public static class TenantNotFoundException extends RuntimeException {
        public TenantNotFoundException(String message) {
            super(message);
        }
    }
}
