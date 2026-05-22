package com.nousresearch.hermes.gateway.stats;

/**
 * 租户统计信息
 */
public class TenantStats {
    private String tenantId;
    private int activeSessions;
    private int totalRequests;
    private long storageUsed;
    private String status;
    
    public TenantStats(String tenantId, int activeSessions, int totalRequests, 
                       long storageUsed, String status) {
        this.tenantId = tenantId;
        this.activeSessions = activeSessions;
        this.totalRequests = totalRequests;
        this.storageUsed = storageUsed;
        this.status = status;
    }
    
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    
    public int getActiveSessions() { return activeSessions; }
    public void setActiveSessions(int activeSessions) { this.activeSessions = activeSessions; }
    
    public int getTotalRequests() { return totalRequests; }
    public void setTotalRequests(int totalRequests) { this.totalRequests = totalRequests; }
    
    public long getStorageUsed() { return storageUsed; }
    public void setStorageUsed(long storageUsed) { this.storageUsed = storageUsed; }
    
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
