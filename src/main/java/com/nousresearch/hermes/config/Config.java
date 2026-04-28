package com.nousresearch.hermes.config;

import java.nio.file.Path;

/**
 * Configuration wrapper for tenant-specific configuration access.
 * Provides convenient access to tenant paths and settings.
 */
public class Config {
    private final Path tenantsDir;
    
    public Config() {
        this.tenantsDir = Constants.getHermesHome().resolve("tenants");
    }
    
    public Config(Path tenantsDir) {
        this.tenantsDir = tenantsDir;
    }
    
    /**
     * Get the tenant configuration path for a given tenant ID.
     */
    public Path getTenantConfigPath(String tenantId) {
        return tenantsDir.resolve(sanitizeTenantId(tenantId)).resolve("config.json");
    }
    
    /**
     * Get the tenants directory.
     */
    public Path getTenantsDir() {
        return tenantsDir;
    }
    
    /**
     * Get the global config manager instance.
     */
    public ConfigManager getConfigManager() {
        return ConfigManager.getInstance();
    }
    
    private String sanitizeTenantId(String tenantId) {
        String sanitized = tenantId.replaceAll("[^a-zA-Z0-9_-]", "_");
        if (sanitized.length() > 64) {
            sanitized = sanitized.substring(0, 64);
        }
        return sanitized.toLowerCase();
    }
}
