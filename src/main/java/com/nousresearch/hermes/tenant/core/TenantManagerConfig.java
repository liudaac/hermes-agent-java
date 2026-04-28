package com.nousresearch.hermes.tenant.core;

import java.time.Duration;

/**
 * 租户管理器配置
 */
public class TenantManagerConfig {
    
    // 最大租户数量
    private int maxTenants = 1000;
    
    // 是否启用闲置清理
    private boolean enableIdleCleanup = true;
    
    // 闲置超时时间
    private Duration idleTimeout = Duration.ofMinutes(30);
    
    // 是否自动加载已有租户
    private boolean autoLoadExisting = false;
    
    // 租户默认存储配额 (MB)
    private long defaultStorageQuota = 1024; // 1GB
    
    // 租户默认内存配额 (MB)
    private long defaultMemoryQuota = 512; // 512MB
    
    // ============ Getters & Setters ============
    
    public int getMaxTenants() { return maxTenants; }
    public void setMaxTenants(int maxTenants) { this.maxTenants = maxTenants; }
    
    public boolean isEnableIdleCleanup() { return enableIdleCleanup; }
    public void setEnableIdleCleanup(boolean enableIdleCleanup) { 
        this.enableIdleCleanup = enableIdleCleanup; 
    }
    
    public Duration getIdleTimeout() { return idleTimeout; }
    public void setIdleTimeout(Duration idleTimeout) { this.idleTimeout = idleTimeout; }
    
    public boolean isAutoLoadExisting() { return autoLoadExisting; }
    public void setAutoLoadExisting(boolean autoLoadExisting) { 
        this.autoLoadExisting = autoLoadExisting; 
    }
    
    public long getDefaultStorageQuota() { return defaultStorageQuota; }
    public void setDefaultStorageQuota(long defaultStorageQuota) { 
        this.defaultStorageQuota = defaultStorageQuota; 
    }
    
    public long getDefaultMemoryQuota() { return defaultMemoryQuota; }
    public void setDefaultMemoryQuota(long defaultMemoryQuota) { 
        this.defaultMemoryQuota = defaultMemoryQuota; 
    }
    
    // ============ Builder ============
    
    public static Builder builder() {
        return new Builder();
    }
    
    public static class Builder {
        private final TenantManagerConfig config = new TenantManagerConfig();
        
        public Builder maxTenants(int max) {
            config.maxTenants = max;
            return this;
        }
        
        public Builder enableIdleCleanup(boolean enable) {
            config.enableIdleCleanup = enable;
            return this;
        }
        
        public Builder idleTimeout(Duration timeout) {
            config.idleTimeout = timeout;
            return this;
        }
        
        public Builder autoLoadExisting(boolean auto) {
            config.autoLoadExisting = auto;
            return this;
        }
        
        public Builder defaultStorageQuota(long quota) {
            config.defaultStorageQuota = quota;
            return this;
        }
        
        public Builder defaultMemoryQuota(long quota) {
            config.defaultMemoryQuota = quota;
            return this;
        }
        
        public TenantManagerConfig build() {
            return config;
        }
    }
}
