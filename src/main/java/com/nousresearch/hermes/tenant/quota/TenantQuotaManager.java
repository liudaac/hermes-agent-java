package com.nousresearch.hermes.tenant.quota;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 租户配额管理器 - 持久化版
 * 
 * 功能：
 * - 配额检查与限制
 * - 使用量追踪与持久化
 * - 跨日期使用量重置
 * - 历史数据保留
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
    
    private volatile LocalDate currentDate = LocalDate.now();
    
    // Jackson ObjectMapper
    private static final ObjectMapper mapper = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    
    // 使用量文件
    private static final String USAGE_FILE = "usage.json";
    private static final String HISTORY_DIR = "history";
    
    public TenantQuotaManager(Path tenantDir, TenantQuota quota) {
        this.quotaDir = tenantDir.resolve("state");
        this.quota = quota;
        
        try {
            Files.createDirectories(quotaDir);
            Files.createDirectories(quotaDir.resolve(HISTORY_DIR));
            loadUsage();
        } catch (IOException e) {
            logger.error("Failed to create quota directory", e);
        }
    }
    
    public static TenantQuotaManager load(Path tenantDir) {
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
        // 持久化
        saveUsageAsync();
    }
    
    public void checkTokenQuota(long tokens) {
        checkAndResetDailyQuota();
        
        if (dailyTokens.addAndGet(tokens) > quota.getMaxDailyTokens()) {
            dailyTokens.addAndGet(-tokens);
            throw new QuotaExceededException("Daily token quota exceeded");
        }
        // 持久化
        saveUsageAsync();
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

    public long getMaxMemoryBytes() {
        return quota.getMaxMemoryBytes();
    }
    
    // ============ 记录方法 ============
    
    public void recordAgentCreated() {
        activeAgents.incrementAndGet();
        saveUsageAsync();
    }
    
    public void recordAgentDestroyed() {
        activeAgents.decrementAndGet();
        saveUsageAsync();
    }
    
    public void recordStorageUsed(long bytes) {
        storageUsage.addAndGet(bytes);
        saveUsageAsync();
    }
    
    public void recordStorageFreed(long bytes) {
        storageUsage.addAndGet(-bytes);
        saveUsageAsync();
    }
    
    public TenantQuota getQuota() {
        return quota;
    }
    
    // ============ Getters ============
    
    public long getMemoryUsage() {
        return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    }
    
    public long getStorageUsage() {
        return storageUsage.get();
    }
    
    public void checkToolCallQuota() {
        checkAndResetDailyQuota();
        
        int currentCalls = dailyRequests.get();
        if (currentCalls >= quota.getMaxToolCallsPerSession()) {
            throw new QuotaExceededException("Tool call quota exceeded for this session");
        }
    }
    
    public void checkDailyRequestQuota() {
        checkRequestQuota();
    }
    
    public void updateQuota(TenantQuota newQuota) {
        this.quota.setMaxDailyRequests(newQuota.getMaxDailyRequests());
        this.quota.setMaxDailyTokens(newQuota.getMaxDailyTokens());
        this.quota.setMaxConcurrentAgents(newQuota.getMaxConcurrentAgents());
        this.quota.setMaxStorageBytes(newQuota.getMaxStorageBytes());
        this.quota.setMaxFileSizeBytes(newQuota.getMaxFileSizeBytes());
        this.quota.setMaxToolCallsPerSession(newQuota.getMaxToolCallsPerSession());
        logger.info("Updated quota for tenant: maxRequests={}, maxTokens={}, maxAgents={}, maxStorage={}",
            newQuota.getMaxDailyRequests(),
            newQuota.getMaxDailyTokens(),
            newQuota.getMaxConcurrentAgents(),
            newQuota.getMaxStorageBytes());
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
    
    // ============ 使用量持久化 ============
    
    /**
     * 加载使用量（从文件）
     */
    private void loadUsage() {
        Path usageFile = quotaDir.resolve(USAGE_FILE);
        
        if (!Files.exists(usageFile)) {
            logger.debug("No usage file found, starting fresh");
            return;
        }
        
        try {
            String json = Files.readString(usageFile);
            QuotaUsageSnapshot snapshot = mapper.readValue(json, QuotaUsageSnapshot.class);
            
            LocalDate lastDate = snapshot.date();
            LocalDate today = LocalDate.now();
            
            if (lastDate.equals(today)) {
                // 同一天，恢复使用量
                dailyRequests.set(snapshot.dailyRequests());
                dailyTokens.set(snapshot.dailyTokens());
                activeAgents.set(snapshot.activeAgents());
                storageUsage.set(snapshot.storageUsage());
                currentDate = lastDate;
                
                logger.info("Loaded today's usage: requests={}, tokens={}", 
                    dailyRequests.get(), dailyTokens.get());
            } else {
                // 新的一天，保存昨天的使用量到历史，然后重置
                saveToHistory(lastDate, snapshot);
                
                // 重置使用量
                dailyRequests.set(0);
                dailyTokens.set(0);
                currentDate = today;
                
                logger.info("New day detected, reset quota usage");
            }
            
        } catch (Exception e) {
            logger.error("Failed to load usage from file: {}", e.getMessage());
        }
    }
    
    /**
     * 同步保存使用量
     */
    public void saveUsage() {
        QuotaUsageSnapshot snapshot = new QuotaUsageSnapshot(
            LocalDate.now(),
            dailyRequests.get(),
            dailyTokens.get(),
            activeAgents.get(),
            storageUsage.get(),
            System.currentTimeMillis()
        );
        
        try {
            String json = mapper.writeValueAsString(snapshot);
            Path usageFile = quotaDir.resolve(USAGE_FILE);
            Files.writeString(usageFile, json);
            logger.debug("Saved usage: {}", snapshot);
        } catch (IOException e) {
            logger.error("Failed to save usage: {}", e.getMessage());
        }
    }
    
    /**
     * 异步保存使用量（后台线程）
     */
    private void saveUsageAsync() {
        Thread.startVirtualThread(() -> {
            try {
                saveUsage();
            } catch (Exception e) {
                logger.debug("Async save failed: {}", e.getMessage());
            }
        });
    }
    
    /**
     * 保存到历史记录
     */
    private void saveToHistory(LocalDate date, QuotaUsageSnapshot snapshot) {
        try {
            Path historyFile = quotaDir.resolve(HISTORY_DIR)
                .resolve(date.format(DateTimeFormatter.ISO_DATE) + ".json");
            String json = mapper.writeValueAsString(snapshot);
            Files.writeString(historyFile, json);
            logger.info("Saved historical usage for {}", date);
            
            // 清理超过30天的历史
            cleanupOldHistory();
            
        } catch (IOException e) {
            logger.error("Failed to save history: {}", e.getMessage());
        }
    }
    
    /**
     * 清理旧的历史记录（超过30天）
     */
    private void cleanupOldHistory() {
        try {
            Path historyDir = quotaDir.resolve(HISTORY_DIR);
            LocalDate cutoff = LocalDate.now().minusDays(30);
            
            Files.list(historyDir)
                .filter(p -> p.toString().endsWith(".json"))
                .forEach(p -> {
                    try {
                        String filename = p.getFileName().toString();
                        LocalDate date = LocalDate.parse(filename.replace(".json", ""));
                        if (date.isBefore(cutoff)) {
                            Files.delete(p);
                            logger.debug("Deleted old history: {}", filename);
                        }
                    } catch (Exception e) {
                        logger.debug("Failed to process history file: {}", p);
                    }
                });
        } catch (Exception e) {
            logger.debug("Failed to cleanup history: {}", e.getMessage());
        }
    }
    
    /**
     * 获取历史使用量
     */
    public QuotaUsageSnapshot getHistoricalUsage(LocalDate date) {
        try {
            Path historyFile = quotaDir.resolve(HISTORY_DIR)
                .resolve(date.format(DateTimeFormatter.ISO_DATE) + ".json");
            if (Files.exists(historyFile)) {
                String json = Files.readString(historyFile);
                return mapper.readValue(json, QuotaUsageSnapshot.class);
            }
        } catch (Exception e) {
            logger.debug("Failed to load historical usage: {}", e.getMessage());
        }
        return null;
    }
    
    // ============ 私有方法 ============
    
    private void checkAndResetDailyQuota() {
        LocalDate today = LocalDate.now();
        if (!today.equals(currentDate)) {
            // 保存昨天使用量到历史
            QuotaUsageSnapshot yesterday = new QuotaUsageSnapshot(
                currentDate,
                dailyRequests.get(),
                dailyTokens.get(),
                activeAgents.get(),
                storageUsage.get(),
                System.currentTimeMillis()
            );
            saveToHistory(currentDate, yesterday);
            
            // 重置
            dailyRequests.set(0);
            dailyTokens.set(0);
            currentDate = today;
            
            logger.info("Daily quota reset for new day: {}", today);
        }
    }
    
    // ============ 快照类 ============
    
    /**
     * 使用量快照（用于持久化）
     */
    public record QuotaUsageSnapshot(
        LocalDate date,
        int dailyRequests,
        long dailyTokens,
        int activeAgents,
        long storageUsage,
        long lastUpdated
    ) {}
    
    // ============ QuotaUsage 记录类 ============
    
    public record QuotaUsage(
        int dailyRequests, int maxDailyRequests,
        long dailyTokens, long maxDailyTokens,
        int activeAgents, int maxConcurrentAgents,
        long storageUsage, long maxStorage
    ) {
        public int getDailyRequests() { return dailyRequests; }
        public int getMaxDailyRequests() { return maxDailyRequests; }
        public long getDailyTokens() { return dailyTokens; }
        public long getMaxDailyTokens() { return maxDailyTokens; }
        public int getActiveAgents() { return activeAgents; }
        public int getMaxConcurrentAgents() { return maxConcurrentAgents; }
        public long getStorageBytes() { return storageUsage; }
        public long getMaxStorageBytes() { return maxStorage; }
        public int getTotalRequests() { return dailyRequests; }
        public long getTotalTokens() { return dailyTokens; }

        public QuotaUsage incrementRequests() {
            return new QuotaUsage(
                dailyRequests + 1,
                maxDailyRequests,
                dailyTokens,
                maxDailyTokens,
                activeAgents,
                maxConcurrentAgents,
                storageUsage,
                maxStorage
            );
        }

        public QuotaUsage incrementDailyRequests() {
            return incrementRequests();
        }
        
        public double getDailyRequestPercent() {
            return maxDailyRequests > 0 ? (double) dailyRequests / maxDailyRequests * 100 : 0;
        }
        
        public double getDailyTokenPercent() {
            return maxDailyTokens > 0 ? (double) dailyTokens / maxDailyTokens * 100 : 0;
        }
        
        public double getStoragePercent() {
            return maxStorage > 0 ? (double) storageUsage / maxStorage * 100 : 0;
        }
    }
}
