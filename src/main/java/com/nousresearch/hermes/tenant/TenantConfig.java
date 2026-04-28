package com.nousresearch.hermes.tenant;

import com.nousresearch.hermes.tenant.quota.TenantQuota;
import com.nousresearch.hermes.tenant.security.TenantSecurityPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

/**
 * 租户配置类
 * 存储租户的基本配置信息
 */
public class TenantConfig {
    private static final ObjectMapper mapper = new ObjectMapper();
    
    private String tenantId;
    private String name;
    private String description;
    private String createdBy;
    private Instant createdAt;
    private TenantQuota quota;
    private TenantSecurityPolicy securityPolicy;
    
    // 文件沙箱配置
    private Map<String, Object> fileSandboxConfig;
    
    private transient Path configPath;
    
    public TenantConfig() {
        this.createdAt = Instant.now();
        this.quota = TenantQuota.defaults();
        this.securityPolicy = new TenantSecurityPolicy();
    }
    
    public TenantConfig(Path configPath, TenantConfig template) {
        this();
        this.configPath = configPath;
        if (template != null) {
            this.tenantId = template.tenantId;
            this.name = template.name;
            this.description = template.description;
            this.createdBy = template.createdBy;
            this.quota = template.quota;
            this.securityPolicy = template.securityPolicy;
            this.fileSandboxConfig = template.fileSandboxConfig;
        }
    }
    
    public static TenantConfig load(Path configPath) throws IOException {
        TenantConfig config = new TenantConfig();
        config.configPath = configPath;
        
        Path configFile = configPath.resolve("tenant.json");
        if (Files.exists(configFile)) {
            ObjectNode node = (ObjectNode) mapper.readTree(configFile.toFile());
            config.tenantId = node.path("tenantId").asText();
            config.name = node.path("name").asText();
            config.description = node.path("description").asText();
            config.createdBy = node.path("createdBy").asText();
            String createdAtStr = node.path("createdAt").asText();
            if (createdAtStr != null && !createdAtStr.isEmpty()) {
                config.createdAt = Instant.parse(createdAtStr);
            }
        }
        
        return config;
    }
    
    public void save() throws IOException {
        if (configPath == null) {
            throw new IllegalStateException("Config path not set");
        }
        
        Files.createDirectories(configPath);
        
        ObjectNode node = mapper.createObjectNode();
        node.put("tenantId", tenantId);
        node.put("name", name);
        node.put("description", description);
        node.put("createdBy", createdBy);
        node.put("createdAt", createdAt.toString());
        
        mapper.writerWithDefaultPrettyPrinter()
            .writeValue(configPath.resolve("tenant.json").toFile(), node);
    }
    
    // ============ Getters & Setters ============
    
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    
    public TenantQuota getQuota() { return quota; }
    public void setQuota(TenantQuota quota) { this.quota = quota; }
    
    public TenantSecurityPolicy getSecurityPolicy() { return securityPolicy; }
    public void setSecurityPolicy(TenantSecurityPolicy securityPolicy) { 
        this.securityPolicy = securityPolicy; 
    }
    
    public Map<String, Object> getFileSandboxConfig() { return fileSandboxConfig; }
    public void setFileSandboxConfig(Map<String, Object> config) { 
        this.fileSandboxConfig = config; 
    }
    
    public String getString(String key, String defaultValue) {
        if (configPath == null) return defaultValue;
        // Simplified implementation
        return defaultValue;
    }
    
    public int getInt(String key, int defaultValue) {
        if (configPath == null) return defaultValue;
        // Simplified implementation
        return defaultValue;
    }
}
