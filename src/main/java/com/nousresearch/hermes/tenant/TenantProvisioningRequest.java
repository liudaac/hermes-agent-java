package com.nousresearch.hermes.tenant;

import com.nousresearch.hermes.tenant.quota.TenantQuota;
import com.nousresearch.hermes.tenant.security.TenantSecurityPolicy;

import java.util.Map;

/**
 * 租户配置请求
 * 用于创建新租户时的配置参数
 */
public class TenantProvisioningRequest {
    private String tenantId;
    private TenantConfig config;
    private TenantQuota quota;
    private TenantSecurityPolicy securityPolicy;
    private Map<String, Object> fileSandboxConfig;
    private String createdBy;
    
    public TenantProvisioningRequest() {}
    
    private TenantProvisioningRequest(Builder builder) {
        this.tenantId = builder.tenantId;
        this.config = builder.config;
        this.quota = builder.quota;
        this.securityPolicy = builder.securityPolicy;
        this.fileSandboxConfig = builder.fileSandboxConfig;
        this.createdBy = builder.createdBy;
    }
    
    public static Builder builder() {
        return new Builder();
    }
    
    // ============ Getters ============
    
    public String getTenantId() { return tenantId; }
    public TenantConfig getConfig() { return config; }
    public TenantQuota getQuota() { return quota; }
    public TenantSecurityPolicy getSecurityPolicy() { return securityPolicy; }
    public Map<String, Object> getFileSandboxConfig() { return fileSandboxConfig; }
    public String getCreatedBy() { return createdBy; }
    
    // ============ Builder ============
    
    public static class Builder {
        private String tenantId;
        private TenantConfig config;
        private TenantQuota quota;
        private TenantSecurityPolicy securityPolicy;
        private Map<String, Object> fileSandboxConfig;
        private String createdBy;
        
        public Builder tenantId(String tenantId) {
            this.tenantId = tenantId;
            return this;
        }
        
        public Builder config(TenantConfig config) {
            this.config = config;
            return this;
        }
        
        public Builder quota(TenantQuota quota) {
            this.quota = quota;
            return this;
        }
        
        public Builder securityPolicy(TenantSecurityPolicy securityPolicy) {
            this.securityPolicy = securityPolicy;
            return this;
        }
        
        public Builder fileSandboxConfig(Map<String, Object> config) {
            this.fileSandboxConfig = config;
            return this;
        }
        
        public Builder createdBy(String createdBy) {
            this.createdBy = createdBy;
            return this;
        }
        
        public TenantProvisioningRequest build() {
            return new TenantProvisioningRequest(this);
        }
    }
}
