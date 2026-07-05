package com.nousresearch.hermes.tenant.quota;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * S2-1 #1: 本地内存配额存储（单实例模式）。
 *
 * <p>使用 AtomicInteger/AtomicLong 保证线程安全。
 * 这是从 {@link TenantQuotaManager} 原有逻辑提取的实现。</p>
 */
public class LocalQuotaStore implements QuotaStore {
    private static final Logger logger = LoggerFactory.getLogger(LocalQuotaStore.class);

    private final AtomicInteger dailyRequests = new AtomicInteger(0);
    private final AtomicLong dailyTokens = new AtomicLong(0);
    private final AtomicInteger activeAgents = new AtomicInteger(0);
    private final AtomicLong storageUsage = new AtomicLong(0);

    private volatile LocalDate currentDate = LocalDate.now();

    @Override
    public long incrementAndGetDailyRequests() {
        return dailyRequests.incrementAndGet();
    }

    @Override
    public void decrementDailyRequests() {
        dailyRequests.decrementAndGet();
    }

    @Override
    public long addAndGetDailyTokens(long tokens) {
        return dailyTokens.addAndGet(tokens);
    }

    @Override
    public void subtractDailyTokens(long tokens) {
        dailyTokens.addAndGet(-tokens);
    }

    @Override
    public void incrementActiveAgents() {
        activeAgents.incrementAndGet();
    }

    @Override
    public void decrementActiveAgents() {
        activeAgents.decrementAndGet();
    }

    @Override
    public long addAndGetStorageUsage(long bytes) {
        return storageUsage.addAndGet(bytes);
    }

    @Override
    public void subtractStorageUsage(long bytes) {
        storageUsage.addAndGet(-bytes);
    }

    @Override
    public long getDailyRequests() { return dailyRequests.get(); }

    @Override
    public long getDailyTokens() { return dailyTokens.get(); }

    @Override
    public int getActiveAgents() { return activeAgents.get(); }

    @Override
    public long getStorageUsage() { return storageUsage.get(); }

    @Override
    public boolean checkAndResetDaily() {
        LocalDate today = LocalDate.now();
        if (!today.equals(currentDate)) {
            dailyRequests.set(0);
            dailyTokens.set(0);
            currentDate = today;
            logger.info("Daily quota reset for new day: {}", today);
            return true;
        }
        return false;
    }

    @Override
    public void saveUsage() {
        // TenantQuotaManager 负责文件持久化，这里 no-op
    }

    @Override
    public void loadUsage() {
        // TenantQuotaManager 通过 loadFromSnapshot 加载文件数据
    }

    /**
     * 从快照恢复使用量（文件持久化恢复用）。
     */
    public void loadFromSnapshot(int requests, long tokens, int agents, long storage) {
        dailyRequests.set(requests);
        dailyTokens.set(tokens);
        activeAgents.set(agents);
        storageUsage.set(storage);
    }

    /**
     * 重置每日计数（跨日时调用）。
     */
    public void resetDaily() {
        dailyRequests.set(0);
        dailyTokens.set(0);
    }
}
