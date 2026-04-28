package com.nousresearch.hermes.tenant.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 租户安全策略
 */
public class TenantSecurityPolicy {
    private static final Logger logger = LoggerFactory.getLogger(TenantSecurityPolicy.class);
    private static final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    private static final ObjectMapper jsonMapper = new ObjectMapper();
    
    // 代码执行策略
    private boolean allowCodeExecution = true;
    private boolean requireSandbox = true;
    private Set<String> allowedLanguages = new HashSet<>(Set.of("python", "javascript"));
    
    // 网络策略
    private boolean allowNetworkAccess = false;
    private Set<String> allowedHosts = new HashSet<>();
    
    // 工具策略
    private Set<String> allowedTools = new HashSet<>();
    private Set<String> deniedTools = new HashSet<>();
    
    // 文件策略
    private boolean allowFileRead = true;
    private boolean allowFileWrite = true;
    private Set<String> deniedPaths = new HashSet<>();
    
    public static TenantSecurityPolicy defaults() {
        return new TenantSecurityPolicy();
    }
    
    /**
     * Load security policy from configuration file
     * Supports both YAML and JSON formats
     */
    public static TenantSecurityPolicy load(Path configDir) {
        Path yamlFile = configDir.resolve("security.yaml");
        Path ymlFile = configDir.resolve("security.yml");
        Path jsonFile = configDir.resolve("security.json");
        
        try {
            // Try YAML first
            if (Files.exists(yamlFile)) {
                return yamlMapper.readValue(yamlFile.toFile(), TenantSecurityPolicy.class);
            }
            if (Files.exists(ymlFile)) {
                return yamlMapper.readValue(ymlFile.toFile(), TenantSecurityPolicy.class);
            }
            // Then JSON
            if (Files.exists(jsonFile)) {
                return jsonMapper.readValue(jsonFile.toFile(), TenantSecurityPolicy.class);
            }
        } catch (IOException e) {
            logger.error("Failed to load security policy from {}: {}", configDir, e.getMessage());
        }
        
        logger.debug("No security policy config found in {}, using defaults", configDir);
        return defaults();
    }
    
    /**
     * Save security policy to YAML file
     */
    public void save(Path configDir) throws IOException {
        Files.createDirectories(configDir);
        Path yamlFile = configDir.resolve("security.yaml");
        yamlMapper.writeValue(yamlFile.toFile(), this);
        logger.info("Saved security policy to {}", yamlFile);
    }
    
    // ============ Getters & Setters ============
    
    public boolean isAllowCodeExecution() { return allowCodeExecution; }
    public void setAllowCodeExecution(boolean allow) { this.allowCodeExecution = allow; }
    
    public boolean isRequireSandbox() { return requireSandbox; }
    public void setRequireSandbox(boolean require) { this.requireSandbox = require; }
    
    public Set<String> getAllowedLanguages() { return allowedLanguages; }
    public void setAllowedLanguages(Set<String> langs) { this.allowedLanguages = langs; }
    
    public boolean isAllowNetworkAccess() { return allowNetworkAccess; }
    public void setAllowNetworkAccess(boolean allow) { this.allowNetworkAccess = allow; }
    
    public Set<String> getAllowedHosts() { return allowedHosts; }
    public void setAllowedHosts(Set<String> hosts) { this.allowedHosts = hosts; }
    
    public Set<String> getAllowedTools() { return allowedTools; }
    public void setAllowedTools(Set<String> tools) { this.allowedTools = tools; }
    
    public Set<String> getDeniedTools() { return deniedTools; }
    public void setDeniedTools(Set<String> tools) { this.deniedTools = tools; }
    
    public boolean isAllowFileRead() { return allowFileRead; }
    public void setAllowFileRead(boolean allow) { this.allowFileRead = allow; }
    
    public boolean isAllowFileWrite() { return allowFileWrite; }
    public void setAllowFileWrite(boolean allow) { this.allowFileWrite = allow; }
    
    public Set<String> getDeniedPaths() { return deniedPaths; }
    public void setDeniedPaths(Set<String> paths) { this.deniedPaths = paths; }
    
    // ============ Helper Methods ============
    
    /**
     * Check if a tool is allowed
     */
    public boolean isToolAllowed(String toolName) {
        if (!deniedTools.isEmpty() && deniedTools.contains(toolName)) {
            return false;
        }
        if (!allowedTools.isEmpty() && !allowedTools.contains(toolName)) {
            return false;
        }
        return true;
    }
    
    /**
     * Check if a language is allowed for code execution
     */
    public boolean isLanguageAllowed(String language) {
        return allowedLanguages.contains(language.toLowerCase());
    }
    
    /**
     * Check if a path is allowed for file operations
     */
    public boolean isPathAllowed(String path) {
        for (String deniedPath : deniedPaths) {
            if (path.contains(deniedPath)) {
                return false;
            }
        }
        return true;
    }
}
