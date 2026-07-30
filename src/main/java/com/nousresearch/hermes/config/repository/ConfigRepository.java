package com.nousresearch.hermes.config.repository;

import com.nousresearch.hermes.config.ModelRoute;
import com.nousresearch.hermes.harness.AgentTemplate;
import com.nousresearch.hermes.platform.ProviderCatalog;
import com.nousresearch.hermes.tenant.quota.TenantQuota;

import java.util.List;
import java.util.Map;

/**
 * Centralized config repository abstraction.
 *
 * <p>Decouples config storage from file-based approach. Enables MySQL
 * (or other DB) backends for multi-instance deployments with hot reload.</p>
 *
 * <p>Implementations:</p>
 * <ul>
 *   <li>{@link LocalConfigRepository} - reads/writes local files (default)</li>
 *   <li>MysqlConfigRepository - reads/writes MySQL (cluster mode)</li>
 * </ul>
 */
public interface ConfigRepository {

    // ============ Model Config ============

    /**
     * Load tenant model configuration values.
     * Returns a map compatible with TenantConfig's internal representation.
     */
    Map<String, Object> loadModelConfig(String tenantId);

    /**
     * Save tenant model configuration.
     */
    void saveModelConfig(String tenantId, Map<String, Object> config);

    /**
     * Get the last-updated timestamp for a tenant's config (epoch millis).
     * Used for cache invalidation via polling.
     */
    long getConfigVersion(String tenantId);

    // ============ API Keys ============

    /**
     * Load all API keys for a tenant.
     * @return map of provider -> apiKey
     */
    Map<String, String> loadApiKeys(String tenantId);

    /**
     * Save a single API key for a tenant.
     */
    void saveApiKey(String tenantId, String provider, String apiKey);

    /**
     * Remove an API key.
     */
    void removeApiKey(String tenantId, String provider);

    // ============ Model Routes ============

    /**
     * Load tenant-level model routes.
     */
    List<ModelRoute> loadModelRoutes(String tenantId);

    /**
     * Save (upsert) a tenant model route.
     */
    void saveModelRoute(String tenantId, ModelRoute route);

    /**
     * Remove a tenant model route by alias.
     */
    void removeModelRoute(String tenantId, String alias);

    // ============ Quota ============

    /**
     * Load tenant quota.
     */
    TenantQuota loadQuota(String tenantId);

    /**
     * Save tenant quota.
     */
    void saveQuota(String tenantId, TenantQuota quota);

    // ============ Platform ============

    /**
     * Load the platform provider catalog.
     */
    ProviderCatalog loadProviderCatalog();

    /**
     * Load platform-level model routes (predefined aliases).
     */
    List<ModelRoute> loadPlatformModelRoutes();

    /**
     * Load platform-managed API key for a provider (代付 key).
     */
    String loadPlatformApiKey(String provider);

    // ============ Agent Templates (P0: Dynamic Specialist Registry) ============

    /**
     * Load all custom agent templates for a tenant.
     * @return map of templateName -> AgentTemplate
     */
    Map<String, AgentTemplate> loadAgentTemplates(String tenantId);

    /**
     * Save (upsert) a custom agent template for a tenant.
     */
    void saveAgentTemplate(String tenantId, String name, AgentTemplate template);

    /**
     * Delete a custom agent template.
     */
    void deleteAgentTemplate(String tenantId, String name);

    // ============ Tenant Settings (KV) ============

    /**
     * Load a tenant-level setting value by key.
     *
     * <p>Used for settings that don't fit the structured tables:
     * memory.decay_policy, memory.summary_batch_size, etc.</p>
     *
     * @param tenantId tenant identifier
     * @param key      setting key (e.g. "memory.decay_policy")
     * @return setting value, or null if not set
     */
    String loadTenantSetting(String tenantId, String key);

    /**
     * Save (upsert) a tenant-level setting.
     *
     * @param tenantId tenant identifier
     * @param key      setting key
     * @param value    setting value
     */
    void saveTenantSetting(String tenantId, String key, String value);

    /**
     * Load all tenant settings as a key-value map.
     *
     * @param tenantId tenant identifier
     * @return map of all settings (never null, may be empty)
     */
    Map<String, String> loadAllTenantSettings(String tenantId);
}
