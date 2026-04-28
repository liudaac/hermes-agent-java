package com.nousresearch.hermes.tenant.quota;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 租户配额管理器
 */
public class TenantQuotaManager {
    private static final Logger logger = LoggerFactory.getLogger(TenantQuotaManager.class);
    
    private final Path quotaDir;
    private final TenantQuota quota;
    
    // 当前使用量
    private final AtomicInteger dailyRequests = new AtomicInteger(0);
    private final AtomicLong dailyTokens = new AtomicLong(0);
    private final AtomicInteger activeAgents = new AtomicInteger(0);
    private final AtomicLong storageUsage = new AtomicLong(0);
    
    private LocalDate currentDate = LocalDate.now();
    
    public TenantQuotaManager(Path tenantDir, TenantQuota quota) {
        this.quotaDir = tenantDir.resolve("state");
        this.quota = quota;
        
        try {
            Files.createDirectories(quotaDir);
            loadUsage();
        } catch (IOException e) {
            logger.error("Failed to create quota directory", e);
        }
    }
    
    public static TenantQuotaManager load(Path tenantDir) {
        // 从文件加载配额配置和使用量
        TenantQuota quota = TenantQuota.defaults();
        return new TenantQuotaManager(tenantDir, quota);
    }
    
    // ============ 配额检查 ============
    
    public void checkRequestQuota() {
        checkAndResetDailyQuota();
        
        if (dailyRequests.incrementAndGet() > quota.getMaxDailyRequests()) {
            dailyRequests.decrementAndGet();
            throw new QuotaExceededException("Daily request quota exceeded");
        }
    }
    
    public void checkTokenQuota(long tokens) {
        checkAndResetDailyQuota();
        
        if (dailyTokens.addAndGet(tokens) > quota.getMaxDailyTokens()) {
            dailyTokens.addAndGet(-tokens);
            throw new QuotaExceededException("Daily token quota exceeded");
        }
    }
    
    public void checkConcurrentAgents(int count) {
        if (count > quota.getMaxConcurrentAgents()) {
            throw new QuotaExceededException("Concurrent agent limit exceeded: " + count + "/" + quota.getMaxConcurrentAgents());
        }
    }
    
    public void checkStorageQuota(long bytes) {
        if (storageUsage.addAndGet(bytes) > quota.getMaxStorageBytes()) {
            storageUsage.addAndGet(-bytes);
            throw new QuotaExceededException("Storage quota exceeded");
        }
    }
    
    public void checkFileSize(long size) {
        if (size > quota.getMaxFileSizeBytes()) {
            throw new QuotaExceededException("File size exceeds limit: " + size + " > " + quota.getMaxFileSizeBytes());
        }
    }
    
    // ============ 记录方法 ============
    
    public void recordAgentCreated() {
        activeAgents.incrementAndGet();
    }
    
    public void recordAgentDestroyed() {
        activeAgents.decrementAndGet();
    }
    
    public void recordStorageUsed(long bytes) {
        storageUsage.addAndGet(bytes);
    }
    
    public void recordStorageFreed(long bytes) {
        storageUsage.addAndGet(-bytes);
    }
    
    /**
     * 获取租户配额配置
     */
    public TenantQuota getQuota() {
        return quota;
    }
    
    // ============ Getters ============
    
    public long getMemoryUsage() {
        // 估算内存使用
        return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    }
    
    public long getStorageUsage() {
        return storageUsage.get();
    }
    
    /**
     * 检查工具调用配额
     */
    public void checkToolCallQuota() {
        checkAndResetDailyQuota();
        
        int currentCalls = dailyRequests.get();
        if (currentCalls >= quota.getMaxToolCallsPerSession()) {
            throw new QuotaExceededException("Tool call quota exceeded for this session");
        }
    }
    
    public QuotaUsage getUsage() {
        return new QuotaUsage(
            dailyRequests.get(),
            quota.getMaxDailyRequests(),
            dailyTokens.get(),
            quota.getMaxDailyTokens(),
            activeAgents.get(),
            quota.getMaxConcurrentAgents(),
            storageUsage.get(),
            quota.getMaxStorageBytes()
        );
    }
    
    // ============ 私有方法 ============
    
    private void checkAndResetDailyQuota() {
        LocalDate today = LocalDate.now();
        if (!today.equals(currentDate)) {
            dailyRequests.set(0);
            dailyTokens.set(0);
            currentDate = today;
        }
    }
    
    private void loadUsage() {
        // 从文件加载使用量
    }
    
    // ============ 记录类 ============
    
    public record QuotaUsage(
        int dailyRequests, int maxDailyRequests,
        long dailyTokens, long maxDailyTokens,
        int activeAgents, int maxConcurrentAgents,
        long storageUsage, long maxStorage
    ) {
        public double getDailyRequestPercent() {
            return (double) dailyRequests / maxDailyRequests * 100;
        }
        
        public double getDailyTokenPercent() {
            return (double) dailyTokens / maxDailyTokens * 100;
        }
        
        public double getStoragePercent() {
            return (double) storageUsage / maxStorage * 100;
        }
    }
}
