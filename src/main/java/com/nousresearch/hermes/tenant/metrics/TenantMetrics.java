package com.nousresearch.hermes.tenant.metrics;

import com.nousresearch.hermes.tenant.core.TenantContext;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 租户级监控指标
 * 
 * 用于暴露 JMX 指标，监控资源使用情况
 */
public class TenantMetrics {
    
    private final String tenantId;
    
    // 进程执行指标
    private final AtomicLong processExecutions = new AtomicLong(0);
    private final AtomicLong processFailures = new AtomicLong(0);
    private final AtomicLong processTimeouts = new AtomicLong(0);
    
    // 网络请求指标
    private final AtomicLong networkRequests = new AtomicLong(0);
    private final AtomicLong networkFailures = new AtomicLong(0);
    private final AtomicLong networkBlocks = new AtomicLong(0);
    
    // 存储使用指标
    private final AtomicLong storageWrites = new AtomicLong(0);
    private final AtomicLong storageQuotaExceeded = new AtomicLong(0);
    
    // 当前值
    private volatile long currentStorageUsage = 0;
    private volatile int activeProcesses = 0;
    private volatile int activeNetworkConnections = 0;
    
    public TenantMetrics(String tenantId) {
        this.tenantId = tenantId;
    }
    
    // ============ 进程指标 ============
    
    public void recordProcessExecution() {
        processExecutions.incrementAndGet();
    }
    
    public void recordProcessFailure() {
        processFailures.incrementAndGet();
    }
    
    public void recordProcessTimeout() {
        processTimeouts.incrementAndGet();
    }
    
    public void setActiveProcesses(int count) {
        this.activeProcesses = count;
    }
    
    // ============ 网络指标 ============
    
    public void recordNetworkRequest() {
        networkRequests.incrementAndGet();
    }
    
    public void recordNetworkFailure() {
        networkFailures.incrementAndGet();
    }
    
    public void recordNetworkBlock() {
        networkBlocks.incrementAndGet();
    }
    
    public void setActiveNetworkConnections(int count) {
        this.activeNetworkConnections = count;
    }
    
    // ============ 存储指标 ============
    
    public void recordStorageWrite(long bytes) {
        storageWrites.incrementAndGet();
    }
    
    public void recordStorageQuotaExceeded() {
        storageQuotaExceeded.incrementAndGet();
    }
    
    public void setCurrentStorageUsage(long bytes) {
        this.currentStorageUsage = bytes;
    }
    
    // ============ Getters for JMX ============
    
    public String getTenantId() { return tenantId; }
    
    public long getProcessExecutions() { return processExecutions.get(); }
    public long getProcessFailures() { return processFailures.get(); }
    public long getProcessTimeouts() { return processTimeouts.get(); }
    public int getActiveProcesses() { return activeProcesses; }
    
    public long getNetworkRequests() { return networkRequests.get(); }
    public long getNetworkFailures() { return networkFailures.get(); }
    public long getNetworkBlocks() { return networkBlocks.get(); }
    public int getActiveNetworkConnections() { return activeNetworkConnections; }
    
    public long getStorageWrites() { return storageWrites.get(); }
    public long getStorageQuotaExceeded() { return storageQuotaExceeded.get(); }
    public long getCurrentStorageUsage() { return currentStorageUsage; }
    
    /**
     * 获取所有指标作为 Map
     */
    public Map<String, Object> getAllMetrics() {
        Map<String, Object> metrics = new ConcurrentHashMap<>();
        metrics.put("tenantId", tenantId);
        metrics.put("processExecutions", processExecutions.get());
        metrics.put("processFailures", processFailures.get());
        metrics.put("processTimeouts", processTimeouts.get());
        metrics.put("activeProcesses", activeProcesses);
        metrics.put("networkRequests", networkRequests.get());
        metrics.put("networkFailures", networkFailures.get());
        metrics.put("networkBlocks", networkBlocks.get());
        metrics.put("storageWrites", storageWrites.get());
        metrics.put("storageQuotaExceeded", storageQuotaExceeded.get());
        metrics.put("currentStorageUsage", currentStorageUsage);
        return metrics;
    }
}
