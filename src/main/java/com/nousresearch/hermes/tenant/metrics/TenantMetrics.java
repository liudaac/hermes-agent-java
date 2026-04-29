package com.nousresearch.hermes.tenant.metrics;

import com.nousresearch.hermes.tenant.core.TenantContext;
import com.nousresearch.hermes.tenant.sandbox.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.*;
import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 租户指标实现类
 * 
 * 实现 JMX MBean 接口，提供：
 * - 实时资源使用指标
 * - 历史统计信息
 * - 告警阈值检测
 * - Prometheus 格式导出
 */
public class TenantMetrics implements TenantMetricsMBean {

    private static final Logger logger = LoggerFactory.getLogger(TenantMetrics.class);
    
    private final TenantContext context;
    private final String tenantId;
    
    // 指标缓存（减少实时计算）
    private volatile Map<String, Object> metricsCache = new HashMap<>();
    private volatile long lastCacheUpdate = 0;
    private static final long CACHE_TTL_MS = 5000; // 5秒缓存
    
    // 历史统计
    private final AtomicLong totalProcessesExecuted = new AtomicLong(0);
    private final AtomicLong processesTimedOut = new AtomicLong(0);
    private final AtomicLong processesOomKilled = new AtomicLong(0);
    private final AtomicLong totalAgentsCreated = new AtomicLong(0);
    private final AtomicLong totalAuditEvents = new AtomicLong(0);
    
    // 告警阈值
    private static final double MEMORY_WARNING_THRESHOLD = 0.8;
    private static final double MEMORY_CRITICAL_THRESHOLD = 0.95;
    private static final double STORAGE_WARNING_THRESHOLD = 0.85;
    private static final double STORAGE_CRITICAL_THRESHOLD = 0.98;
    
    public TenantMetrics(TenantContext context) {
        this.context = context;
        this.tenantId = context.getTenantId();
        registerMBean();
    }
    
    /**
     * 注册 JMX MBean
     */
    private void registerMBean() {
        try {
            MBeanServer mBeanServer = ManagementFactory.getPlatformMBeanServer();
            ObjectName objectName = new ObjectName(
                "com.nousresearch.hermes:type=TenantMetrics,tenant=" + tenantId
            );
            
            // 如果已存在则先注销
            if (mBeanServer.isRegistered(objectName)) {
                mBeanServer.unregisterMBean(objectName);
            }
            
            mBeanServer.registerMBean(this, objectName);
            logger.debug("Registered JMX MBean for tenant: {}", tenantId);
            
        } catch (Exception e) {
            logger.error("Failed to register JMX MBean for tenant: {}", tenantId, e);
        }
    }
    
    /**
     * 注销 JMX MBean
     */
    public void unregister() {
        try {
            MBeanServer mBeanServer = ManagementFactory.getPlatformMBeanServer();
            ObjectName objectName = new ObjectName(
                "com.nousresearch.hermes:type=TenantMetrics,tenant=" + tenantId
            );
            
            if (mBeanServer.isRegistered(objectName)) {
                mBeanServer.unregisterMBean(objectName);
                logger.debug("Unregistered JMX MBean for tenant: {}", tenantId);
            }
            
        } catch (Exception e) {
            logger.error("Failed to unregister JMX MBean for tenant: {}", tenantId, e);
        }
    }
    
    // ============ 指标更新方法 ============
    
    public void recordProcessExecuted() {
        totalProcessesExecuted.incrementAndGet();
    }
    
    public void recordProcessTimedOut() {
        processesTimedOut.incrementAndGet();
    }
    
    public void recordProcessOomKilled() {
        processesOomKilled.incrementAndGet();
    }
    
    public void recordAgentCreated() {
        totalAgentsCreated.incrementAndGet();
    }
    
    public void recordAuditEvent() {
        totalAuditEvents.incrementAndGet();
    }
    
    // ============ MBean 实现 ============
    
    @Override
    public String getTenantId() {
        return tenantId;
    }
    
    @Override
    public String getState() {
        return context.getState().name();
    }
    
    @Override
    public long getCreatedAt() {
        return context.getCreatedAt().toEpochMilli();
    }
    
    @Override
    public long getLastActivity() {
        return context.getLastActivity().toEpochMilli();
    }
    
    // ============ 内存指标 ============
    
    @Override
    public long getMemoryMaxBytes() {
        TenantMemoryPool.MemoryStats stats = context.getMemoryStats();
        return stats != null ? stats.maxBytes() : 0;
    }
    
    @Override
    public long getMemoryUsedBytes() {
        TenantMemoryPool.MemoryStats stats = context.getMemoryStats();
        return stats != null ? stats.usedBytes() : 0;
    }
    
    @Override
    public long getMemoryAvailableBytes() {
        TenantMemoryPool.MemoryStats stats = context.getMemoryStats();
        return stats != null ? stats.availableBytes() : 0;
    }
    
    @Override
    public double getMemoryUsagePercent() {
        TenantMemoryPool.MemoryStats stats = context.getMemoryStats();
        return stats != null ? stats.usagePercent() : 0.0;
    }
    
    @Override
    public int getMemoryAllocationCount() {
        TenantMemoryPool.MemoryStats stats = context.getMemoryStats();
        return stats != null ? stats.allocationCount() : 0;
    }
    
    @Override
    public int getMemoryLeakCount() {
        TenantMemoryPool.MemoryStats stats = context.getMemoryStats();
        return stats != null ? stats.potentialLeaks().size() : 0;
    }
    
    // ============ 进程指标 ============
    
    @Override
    public int getActiveProcesses() {
        // TODO: 从 cgroup 或进程管理器获取
        return 0;
    }
    
    @Override
    public long getTotalProcessesExecuted() {
        return totalProcessesExecuted.get();
    }
    
    @Override
    public long getProcessesTimedOut() {
        return processesTimedOut.get();
    }
    
    @Override
    public long getProcessesOomKilled() {
        return processesOomKilled.get();
    }
    
    // ============ 网络指标 ============
    
    @Override
    public int getNetworkTotalRequests() {
        RestrictedHttpClient.NetworkStats stats = context.getNetworkStats();
        return stats != null ? stats.getTotalRequests() : 0;
    }
    
    @Override
    public int getNetworkBlockedRequests() {
        RestrictedHttpClient.NetworkStats stats = context.getNetworkStats();
        return stats != null ? stats.getBlockedRequests() : 0;
    }
    
    @Override
    public double getNetworkBlockRate() {
        RestrictedHttpClient.NetworkStats stats = context.getNetworkStats();
        return stats != null ? stats.getBlockRate() : 0.0;
    }
    
    @Override
    public int getNetworkRequestsPerSecond() {
        // TODO: 计算 RPS
        return 0;
    }
    
    @Override
    public Map<String, Integer> getNetworkHostCounts() {
        RestrictedHttpClient.NetworkStats stats = context.getNetworkStats();
        if (stats == null || stats.getHostCounts() == null) {
            return Map.of();
        }
        
        Map<String, Integer> result = new HashMap<>();
        stats.getHostCounts().forEach((host, count) -> {
            result.put(host, count.get());
        });
        return result;
    }
    
    // ============ 文件系统指标 ============
    
    @Override
    public long getStorageQuotaBytes() {
        // TODO: 从存储配额获取
        return 0;
    }
    
    @Override
    public long getStorageUsedBytes() {
        // TODO: 从文件沙箱获取
        return 0;
    }
    
    @Override
    public long getStorageAvailableBytes() {
        long quota = getStorageQuotaBytes();
        long used = getStorageUsedBytes();
        return quota - used;
    }
    
    @Override
    public double getStorageUsagePercent() {
        long quota = getStorageQuotaBytes();
        long used = getStorageUsedBytes();
        return quota > 0 ? (double) used / quota : 0.0;
    }
    
    @Override
    public int getFileCount() {
        // TODO: 从文件沙箱获取
        return 0;
    }
    
    // ============ Agent 指标 ============
    
    @Override
    public int getActiveAgents() {
        return context.getActiveAgentCount();
    }
    
    @Override
    public long getTotalAgentsCreated() {
        return totalAgentsCreated.get();
    }
    
    @Override
    public int getActiveSessions() {
        return context.getActiveSessionCount();
    }
    
    // ============ 审计指标 ============
    
    @Override
    public long getTotalAuditEvents() {
        return totalAuditEvents.get();
    }
    
    @Override
    public long getAuditEventsLastHour() {
        // TODO: 从审计日志获取
        return 0;
    }
    
    // ============ 配额指标 ============
    
    @Override
    public boolean isQuotaWarning() {
        return getMemoryUsagePercent() >= MEMORY_WARNING_THRESHOLD ||
               getStorageUsagePercent() >= STORAGE_WARNING_THRESHOLD;
    }
    
    @Override
    public boolean isQuotaExceeded() {
        return getMemoryUsagePercent() >= MEMORY_CRITICAL_THRESHOLD ||
               getStorageUsagePercent() >= STORAGE_CRITICAL_THRESHOLD;
    }
    
    @Override
    public String getQuotaStatus() {
        if (isQuotaExceeded()) {
            return "CRITICAL";
        } else if (isQuotaWarning()) {
            return "WARNING";
        }
        return "OK";
    }
    
    // ============ 操作 ============
    
    @Override
    public void triggerGC() {
        System.gc();
        logger.info("GC triggered for tenant: {}", tenantId);
    }
    
    @Override
    public void compactMemory() {
        if (context.getMemoryPool() != null) {
            context.getMemoryPool().compact();
            logger.info("Memory compacted for tenant: {}", tenantId);
        }
    }
    
    @Override
    public String generateReport() {
        StringBuilder report = new StringBuilder();
        report.append("=== Tenant Metrics Report ===\n");
        report.append("Tenant ID: ").append(tenantId).append("\n");
        report.append("State: ").append(getState()).append("\n");
        report.append("\n");
        
        report.append("--- Memory ---\n");
        report.append(String.format("Usage: %.2f%% (%d MB / %d MB)\n",
            getMemoryUsagePercent() * 100,
            getMemoryUsedBytes() / (1024 * 1024),
            getMemoryMaxBytes() / (1024 * 1024)));
        report.append("Allocations: ").append(getMemoryAllocationCount()).append("\n");
        report.append("Leaks: ").append(getMemoryLeakCount()).append("\n");
        report.append("\n");
        
        report.append("--- Network ---\n");
        report.append("Total Requests: ").append(getNetworkTotalRequests()).append("\n");
        report.append("Blocked: ").append(getNetworkBlockedRequests()).append("\n");
        report.append(String.format("Block Rate: %.2f%%\n", getNetworkBlockRate() * 100));
        report.append("\n");
        
        report.append("--- Agents ---\n");
        report.append("Active: ").append(getActiveAgents()).append("\n");
        report.append("Sessions: ").append(getActiveSessions()).append("\n");
        report.append("\n");
        
        report.append("--- Quota Status ---\n");
        report.append("Status: ").append(getQuotaStatus()).append("\n");
        
        return report.toString();
    }
    
    // ============ Prometheus 导出 ============
    
    /**
     * 导出 Prometheus 格式的指标
     */
    public String exportPrometheusMetrics() {
        StringBuilder sb = new StringBuilder();
        
        // 帮助信息
        sb.append("# HELP hermes_tenant_memory_used_bytes Tenant memory used in bytes\n");
        sb.append("# TYPE hermes_tenant_memory_used_bytes gauge\n");
        sb.append(String.format("hermes_tenant_memory_used_bytes{tenant=\"%s\"} %d\n", 
            tenantId, getMemoryUsedBytes()));
        
        sb.append("# HELP hermes_tenant_memory_max_bytes Tenant memory max in bytes\n");
        sb.append("# TYPE hermes_tenant_memory_max_bytes gauge\n");
        sb.append(String.format("hermes_tenant_memory_max_bytes{tenant=\"%s\"} %d\n", 
            tenantId, getMemoryMaxBytes()));
        
        sb.append("# HELP hermes_tenant_memory_usage_percent Tenant memory usage percent\n");
        sb.append("# TYPE hermes_tenant_memory_usage_percent gauge\n");
        sb.append(String.format("hermes_tenant_memory_usage_percent{tenant=\"%s\"} %.4f\n", 
            tenantId, getMemoryUsagePercent()));
        
        sb.append("# HELP hermes_tenant_network_requests_total Total network requests\n");
        sb.append("# TYPE hermes_tenant_network_requests_total counter\n");
        sb.append(String.format("hermes_tenant_network_requests_total{tenant=\"%s\"} %d\n", 
            tenantId, getNetworkTotalRequests()));
        
        sb.append("# HELP hermes_tenant_network_blocked_requests_total Blocked network requests\n");
        sb.append("# TYPE hermes_tenant_network_blocked_requests_total counter\n");
        sb.append(String.format("hermes_tenant_network_blocked_requests_total{tenant=\"%s\"} %d\n", 
            tenantId, getNetworkBlockedRequests()));
        
        sb.append("# HELP hermes_tenant_active_agents Active agent count\n");
        sb.append("# TYPE hermes_tenant_active_agents gauge\n");
        sb.append(String.format("hermes_tenant_active_agents{tenant=\"%s\"} %d\n", 
            tenantId, getActiveAgents()));
        
        sb.append("# HELP hermes_tenant_active_sessions Active session count\n");
        sb.append("# TYPE hermes_tenant_active_sessions gauge\n");
        sb.append(String.format("hermes_tenant_active_sessions{tenant=\"%s\"} %d\n", 
            tenantId, getActiveSessions()));
        
        sb.append("# HELP hermes_tenant_state Tenant state (0=DESTROYED, 1=SUSPENDED, 2=ACTIVE)\n");
        sb.append("# TYPE hermes_tenant_state gauge\n");
        int stateValue = switch (context.getState()) {
            case DESTROYED -> 0;
            case SUSPENDED -> 1;
            case ACTIVE -> 2;
            default -> -1;
        };
        sb.append(String.format("hermes_tenant_state{tenant=\"%s\"} %d\n", 
            tenantId, stateValue));
        
        return sb.toString();
    }
}
