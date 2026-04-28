package com.nousresearch.hermes.tenant.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nousresearch.hermes.config.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 租户管理器
 * 管理所有租户的生命周期，提供租户的创建、获取、清理等功能
 */
public class TenantManager {
    private static final Logger logger = LoggerFactory.getLogger(TenantManager.class);
    
    private static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofMinutes(30);
    private static final long CLEANUP_INTERVAL_MS = 300_000; // 5分钟
    
    private final Path tenantsDir;
    private final ConcurrentHashMap<String, TenantContext> tenants;
    private final ReentrantReadWriteLock registryLock;
    private final ScheduledExecutorService cleanupExecutor;
    private final TenantRegistry registry;
    
    // 全局配置
    private final TenantManagerConfig config;
    
    public TenantManager() {
        this(Constants.getHermesHome().resolve("tenants"), new TenantManagerConfig());
    }
    
    public TenantManager(Path tenantsDir, TenantManagerConfig config) {
        this.tenantsDir = tenantsDir;
        this.config = config;
        this.tenants = new ConcurrentHashMap<>();
        this.registryLock = new ReentrantReadWriteLock();
        this.registry = new TenantRegistry(tenantsDir.resolve("_system/tenants.json"));
        
        // 确保目录存在
        try {
            Files.createDirectories(tenantsDir);
            Files.createDirectories(tenantsDir.resolve("_system"));
            Files.createDirectories(tenantsDir.resolve("_shared/skills"));
        } catch (IOException e) {
            logger.error("Failed to create tenants directory: {}", e.getMessage());
        }
        
        // 启动清理定时器
        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "tenant-cleanup");
            t.setDaemon(true);
            return t;
        });
        
        if (config.isEnableIdleCleanup()) {
            startCleanupTask();
        }
        
        // 加载现有租户
        loadExistingTenants();
    }
    
    // ============ 租户生命周期管理 ============
    
    /**
     * 创建新租户
     */
    public TenantContext createTenant(TenantProvisioningRequest request) {
        String tenantId = request.getTenantId();
        
        registryLock.writeLock().lock();
        try {
            // 检查是否已存在
            if (tenants.containsKey(tenantId)) {
                throw new TenantAlreadyExistsException("Tenant already exists: " + tenantId);
            }
            
            // 检查租户数量限制
            if (tenants.size() >= config.getMaxTenants()) {
                throw new TenantLimitExceededException("Maximum number of tenants reached: " + config.getMaxTenants());
            }
            
            // 创建租户上下文
            TenantContext context = TenantContext.create(tenantId, request);
            
            // 注册到管理器
            tenants.put(tenantId, context);
            registry.register(tenantId, request);
            
            logger.info("Tenant created and registered: {}", tenantId);
            return context;
            
        } finally {
            registryLock.writeLock().unlock();
        }
    }
    
    /**
     * 获取或创建租户
     */
    public TenantContext getOrCreateTenant(String tenantId, TenantProvisioningRequest request) {
        return tenants.computeIfAbsent(tenantId, id -> {
            // 尝试从磁盘加载
            Path tenantDir = tenantsDir.resolve(sanitizeTenantId(tenantId));
            if (Files.exists(tenantDir)) {
                logger.info("Loading existing tenant: {}", tenantId);
                return TenantContext.load(tenantId);
            }
            
            // 创建新租户
            logger.info("Creating new tenant on demand: {}", tenantId);
            return TenantContext.create(tenantId, request);
        });
    }
    
    /**
     * 获取租户（如果不存在返回 null）
     */
    public TenantContext getTenant(String tenantId) {
        return tenants.get(tenantId);
    }
    
    /**
     * 获取或加载租户
     */
    public TenantContext getOrLoadTenant(String tenantId) {
        TenantContext context = tenants.get(tenantId);
        if (context != null) {
            return context;
        }
        
        // 尝试从磁盘加载
        registryLock.readLock().lock();
        try {
            if (registry.isRegistered(tenantId)) {
                context = TenantContext.load(tenantId);
                tenants.put(tenantId, context);
                return context;
            }
        } finally {
            registryLock.readLock().unlock();
        }
        
        return null;
    }
    
    /**
     * 销毁租户
     */
    public void destroyTenant(String tenantId, boolean preserveData) {
        registryLock.writeLock().lock();
        try {
            TenantContext context = tenants.remove(tenantId);
            if (context != null) {
                context.destroy(preserveData);
            }
            
            registry.unregister(tenantId);
            
            logger.info("Tenant destroyed: {} (preserveData={})", tenantId, preserveData);
        } finally {
            registryLock.writeLock().unlock();
        }
    }
    
    /**
     * 删除租户（默认不保留数据）
     */
    public void deleteTenant(String tenantId) {
        destroyTenant(tenantId, false);
    }
    
    /**
     * 配置/创建租户（API 兼容方法）
     */
    public TenantContext provisionTenant(TenantProvisioningRequest request) {
        return createTenant(request);
    }
    
    /**
     * 检查租户是否已注册
     */
    public boolean isRegistered(String tenantId) {
        return exists(tenantId);
    }
    
    /**
     * 暂停租户（带原因）
     */
    public void suspendTenant(String tenantId, String reason) {
        TenantContext context = tenants.get(tenantId);
        if (context != null) {
            context.suspend(reason);
        }
    }
    
    /**
     * 暂停租户（默认原因）
     */
    public void suspendTenant(String tenantId) {
        suspendTenant(tenantId, "Suspended by administrator");
    }
    
    /**
     * 恢复租户
     */
    public void resumeTenant(String tenantId) {
        TenantContext context = tenants.get(tenantId);
        if (context != null) {
            context.resume();
        }
    }
    
    // ============ 查询方法 ============
    
    /**
     * 列出所有活跃租户
     */
    public Collection<TenantContext> listActiveTenants() {
        return Collections.unmodifiableCollection(tenants.values());
    }
    
    /**
     * 列出所有已注册租户ID
     */
    public Set<String> listRegisteredTenants() {
        registryLock.readLock().lock();
        try {
            return registry.listAll();
        } finally {
            registryLock.readLock().unlock();
        }
    }
    
    /**
     * 检查租户是否已注册
     */
    public boolean isRegistered(String tenantId) {
        if (tenants.containsKey(tenantId)) {
            return true;
        }
        
        registryLock.readLock().lock();
        try {
            return registry.isRegistered(tenantId);
        } finally {
            registryLock.readLock().unlock();
        }
    }
    
    /**
     * 删除租户
     */
    public void deleteTenant(String tenantId) {
        destroyTenant(tenantId, false);
    }
    
    /**
     * 暂停租户（单参数版本）
     */
    public void suspendTenant(String tenantId) {
        suspendTenant(tenantId, "api_request");
    }
    
    /**
     * 检查租户是否存在（别名方法）
     */
    public boolean exists(String tenantId) {
        return isRegistered(tenantId);
    }
    
    /**
     * 获取租户统计信息
     */
    public TenantStats getStats() {
        int activeCount = 0;
        int suspendedCount = 0;
        long totalMemoryUsage = 0;
        
        for (TenantContext context : tenants.values()) {
            if (context.isActive()) {
                activeCount++;
            } else {
                suspendedCount++;
            }
            
            try {
                totalMemoryUsage += context.getQuotaManager().getMemoryUsage();
            } catch (Exception e) {
                logger.debug("Failed to get memory usage for tenant: {}", context.getTenantId());
            }
        }
        
        return new TenantStats(
            tenants.size(),
            activeCount,
            suspendedCount,
            registry.listAll().size(),
            totalMemoryUsage
        );
    }
    
    // ============ 清理任务 ============
    
    private void startCleanupTask() {
        cleanupExecutor.scheduleWithFixedDelay(
            this::cleanupIdleTenants,
            CLEANUP_INTERVAL_MS,
            CLEANUP_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        );
        logger.info("Started tenant cleanup task (interval: {}ms)", CLEANUP_INTERVAL_MS);
    }
    
    /**
     * 清理闲置租户
     */
    public void cleanupIdleTenants() {
        logger.debug("Running tenant cleanup task");
        
        List<String> toRemove = new ArrayList<>();
        
        for (Map.Entry<String, TenantContext> entry : tenants.entrySet()) {
            TenantContext context = entry.getValue();
            
            // 检查是否闲置
            if (context.isIdle(config.getIdleTimeout().toMinutes(), TimeUnit.MINUTES)) {
                // 检查是否有活跃 Agent
                if (context.getActiveAgents().isEmpty()) {
                    toRemove.add(entry.getKey());
                    logger.info("Tenant {} is idle, scheduling for cleanup", entry.getKey());
                }
            }
        }
        
        // 清理闲置租户
        for (String tenantId : toRemove) {
            try {
                destroyTenant(tenantId, true); // 保留数据，只释放内存
            } catch (Exception e) {
                logger.error("Failed to cleanup tenant: {}", tenantId, e);
            }
        }
        
        if (!toRemove.isEmpty()) {
            logger.info("Cleaned up {} idle tenants", toRemove.size());
        }
    }
    
    /**
     * 关闭管理器
     */
    public void shutdown() {
        logger.info("Shutting down TenantManager");
        
        cleanupExecutor.shutdown();
        try {
            if (!cleanupExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                cleanupExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            cleanupExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        // 持久化所有租户状态
        for (TenantContext context : tenants.values()) {
            try {
                context.getSessionManager().persistAll();
            } catch (Exception e) {
                logger.error("Failed to persist tenant: {}", context.getTenantId(), e);
            }
        }
        
        logger.info("TenantManager shutdown complete");
    }
    
    // ============ 私有方法 ============
    
    private void loadExistingTenants() {
        try {
            Set<String> registered = registry.listAll();
            logger.info("Found {} registered tenants", registered.size());
            
            // 延迟加载：不立即加载所有租户，而是在首次访问时加载
            // 这里只验证目录存在
            for (String tenantId : registered) {
                Path tenantDir = tenantsDir.resolve(sanitizeTenantId(tenantId));
                if (!Files.exists(tenantDir)) {
                    logger.warn("Tenant directory missing for registered tenant: {}", tenantId);
                    registry.unregister(tenantId);
                }
            }
            
        } catch (Exception e) {
            logger.error("Failed to load tenant registry", e);
        }
    }
    
    private String sanitizeTenantId(String tenantId) {
        String sanitized = tenantId.replaceAll("[^a-zA-Z0-9_-]", "_");
        if (sanitized.length() > 64) {
            sanitized = sanitized.substring(0, 64);
        }
        return sanitized.toLowerCase();
    }
    
    // ============ 内部类 ============
    
    /**
     * 租户注册表（持久化）
     * Uses Jackson for JSON serialization
     */
    private static class TenantRegistry {
        private static final ObjectMapper mapper = new ObjectMapper();
        private final Path registryFile;
        private final Map<String, TenantRegistryEntry> registeredTenants;
        
        TenantRegistry(Path registryFile) {
            this.registryFile = registryFile;
            this.registeredTenants = new ConcurrentHashMap<>();
            load();
        }
        
        synchronized void register(String tenantId, TenantProvisioningRequest request) {
            TenantRegistryEntry entry = new TenantRegistryEntry(
                tenantId,
                Instant.now(),
                request.getCreatedBy(),
                request.getQuota() != null ? request.getQuota().toMap() : Map.of()
            );
            registeredTenants.put(tenantId, entry);
            persist();
        }
        
        synchronized void unregister(String tenantId) {
            registeredTenants.remove(tenantId);
            persist();
        }
        
        boolean isRegistered(String tenantId) {
            return registeredTenants.containsKey(tenantId);
        }
        
        Set<String> listAll() {
            return new HashSet<>(registeredTenants.keySet());
        }
        
        TenantRegistryEntry getEntry(String tenantId) {
            return registeredTenants.get(tenantId);
        }
        
        private void load() {
            try {
                if (Files.exists(registryFile)) {
                    JsonNode root = mapper.readTree(registryFile.toFile());
                    JsonNode tenants = root.path("tenants");
                    
                    if (tenants.isArray()) {
                        for (JsonNode tenant : tenants) {
                            String tenantId = tenant.path("id").asText();
                            if (!tenantId.isEmpty()) {
                                TenantRegistryEntry entry = mapper.treeToValue(tenant, TenantRegistryEntry.class);
                                registeredTenants.put(tenantId, entry);
                            }
                        }
                    }
                    
                    logger.info("Loaded {} tenants from registry", registeredTenants.size());
                }
            } catch (IOException e) {
                logger.error("Failed to load tenant registry: {}", e.getMessage());
            }
        }
        
        private void persist() {
            try {
                Files.createDirectories(registryFile.getParent());
                
                ObjectNode root = mapper.createObjectNode();
                ArrayNode tenants = root.putArray("tenants");
                
                for (TenantRegistryEntry entry : registeredTenants.values()) {
                    tenants.add(mapper.valueToTree(entry));
                }
                
                mapper.writerWithDefaultPrettyPrinter().writeValue(registryFile.toFile(), root);
                logger.debug("Persisted {} tenants to registry", registeredTenants.size());
                
            } catch (IOException e) {
                logger.error("Failed to persist tenant registry: {}", e.getMessage());
            }
        }
    }
    
    /**
     * Registry entry for a tenant
     */
    private record TenantRegistryEntry(
        String id,
        Instant createdAt,
        String createdBy,
        Map<String, Object> quota
    ) {}
    
    // ============ 记录类 ============
    
    public record TenantStats(
        int activeInMemory,
        int activeTenants,
        int suspendedTenants,
        int totalRegistered,
        long totalMemoryUsage
    ) {}
    
    public record TenantInfo(
        String tenantId,
        Instant createdAt,
        String createdBy,
        Map<String, Object> metadata
    ) {}
    
    // ============ 异常类 ============
    
    public static class TenantAlreadyExistsException extends RuntimeException {
        public TenantAlreadyExistsException(String message) {
            super(message);
        }
    }
    
    public static class TenantLimitExceededException extends RuntimeException {
        public TenantLimitExceededException(String message) {
            super(message);
        }
    }
}
