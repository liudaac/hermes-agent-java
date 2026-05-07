package com.nousresearch.hermes.tenant.core;

import com.nousresearch.hermes.tenant.quota.TenantQuota;
import com.nousresearch.hermes.tenant.sandbox.FileSandboxConfig;
import com.nousresearch.hermes.tenant.sandbox.NetworkPolicy;
import com.nousresearch.hermes.tenant.sandbox.ProcessSandboxConfig;
import com.nousresearch.hermes.tenant.security.TenantSecurityPolicy;

import java.util.Map;

/**
 * 租户配置请求
 * 用于创建新租户时的参数配置
 */
public class TenantProvisioningRequest {
    
    private String tenantId;
    private String tenantName;
    private String createdBy;
    private String description;
    
    // 配额配置
    private TenantQuota quota = TenantQuota.defaults();
    
    // 文件沙箱配置
    private FileSandboxConfig fileSandboxConfig = FileSandboxConfig.defaults();

    // 进程沙箱配置
    private ProcessSandboxConfig processSandboxConfig = ProcessSandboxConfig.defaultConfig();

    // 网络策略配置
    private NetworkPolicy networkPolicy = NetworkPolicy.defaultPolicy();

    // 安全配置
    private TenantSecurityPolicy securityPolicy = TenantSecurityPolicy.defaults();
    
    // 初始配置
    private Map<String, Object> config = Map.of();
    
    // 是否继承系统 Skills
    private boolean inheritSystemSkills = true;
    
    // 是否启用审计
    private boolean enableAudit = true;
    
    public TenantProvisioningRequest(String tenantId, String createdBy) {
        this.tenantId = tenantId;
        this.createdBy = createdBy;
    }
    
    // ============ Builder 模式 ============
    
    public static Builder builder(String tenantId, String createdBy) {
        return new Builder(tenantId, createdBy);
    }
    
    public static class Builder {
        private final TenantProvisioningRequest request;
        
        private Builder(String tenantId, String createdBy) {
            request = new TenantProvisioningRequest(tenantId, createdBy);
        }
        
        public Builder tenantName(String name) {
            request.tenantName = name;
            return this;
        }
        
        public Builder description(String description) {
            request.description = description;
            return this;
        }
        
        public Builder quota(TenantQuota quota) {
            request.quota = quota;
            return this;
        }
        
        public Builder fileSandboxConfig(FileSandboxConfig config) {
            request.fileSandboxConfig = config;
            return this;
        }
        
        public Builder securityPolicy(TenantSecurityPolicy policy) {
            request.securityPolicy = policy;
            return this;
        }
        
        public Builder config(Map<String, Object> config) {
            request.config = config;
            return this;
        }
        
        public Builder inheritSystemSkills(boolean inherit) {
            request.inheritSystemSkills = inherit;
            return this;
        }
        
        public Builder enableAudit(boolean enable) {
            request.enableAudit = enable;
            return this;
        }
        
        public TenantProvisioningRequest build() {
            return request;
        }
    }
    
    // ============ Getters ============

    public String getTenantId() { return tenantId; }
    public String getTenantName() { return tenantName; }
    public String getCreatedBy() { return createdBy; }
    public String getDescription() { return description; }
    public TenantQuota getQuota() { return quota; }
    public FileSandboxConfig getFileSandboxConfig() { return fileSandboxConfig; }
    public ProcessSandboxConfig getProcessSandboxConfig() { return processSandboxConfig; }
    public NetworkPolicy getNetworkPolicy() { return networkPolicy; }
    public TenantSecurityPolicy getSecurityPolicy() { return securityPolicy; }
    public Map<String, Object> getConfig() { return config; }
    public boolean isInheritSystemSkills() { return inheritSystemSkills; }
    public boolean isEnableAudit() { return enableAudit; }

    /**
     * 获取最大内存配额（字节）
     */
    public long getMaxMemoryBytes() {
        return quota != null ? quota.getMaxMemoryBytes() : 128L * 1024 * 1024; // 默认128MB
    }
}
