package com.nousresearch.hermes.tenant.metrics;

import java.util.Map;

/**
 * 租户指标 MBean 接口
 * 
 * 通过 JMX 暴露租户资源使用指标，供监控工具采集
 */
public interface TenantMetricsMBean {

    // ============ 基本信息 ============
    
    String getTenantId();
    String getState();
    long getCreatedAt();
    long getLastActivity();
    
    // ============ 内存指标 ============
    
    long getMemoryMaxBytes();
    long getMemoryUsedBytes();
    long getMemoryAvailableBytes();
    double getMemoryUsagePercent();
    int getMemoryAllocationCount();
    int getMemoryLeakCount();
    
    // ============ 进程指标 ============
    
    int getActiveProcesses();
    long getTotalProcessesExecuted();
    long getProcessesTimedOut();
    long getProcessesOomKilled();
    
    // ============ 网络指标 ============
    
    int getNetworkTotalRequests();
    int getNetworkBlockedRequests();
    double getNetworkBlockRate();
    int getNetworkRequestsPerSecond();
    Map<String, Integer> getNetworkHostCounts();
    
    // ============ 文件系统指标 ============
    
    long getStorageQuotaBytes();
    long getStorageUsedBytes();
    long getStorageAvailableBytes();
    double getStorageUsagePercent();
    int getFileCount();
    
    // ============ Agent 指标 ============
    
    int getActiveAgents();
    long getTotalAgentsCreated();
    int getActiveSessions();
    
    // ============ 审计指标 ============
    
    long getTotalAuditEvents();
    long getAuditEventsLastHour();
    
    // ============ 配额指标 ============
    
    boolean isQuotaWarning();
    boolean isQuotaExceeded();
    String getQuotaStatus();
    
    // ============ 操作 ============
    
    void triggerGC();
    void compactMemory();
    String generateReport();
}
