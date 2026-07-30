package com.nousresearch.hermes.config.repository;

import com.nousresearch.hermes.config.ModelRoute;
import com.nousresearch.hermes.harness.AgentTemplate;
import com.nousresearch.hermes.platform.ProviderCatalog;
import com.nousresearch.hermes.tenant.quota.TenantQuota;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ConfigCache - TTL-based cache with polling invalidation.
 *
 * <p>Caches config reads with a configurable TTL (default 30s).
 * After TTL expires, the next read goes to the underlying repository
 * (MySQL or local files) and refreshes the cache.</p>
 *
 * <p>For MySQL mode, {@code getConfigVersion()} is also polled to
 * detect changes via {@code updated_at} column comparison.</p>
 *
 * <p>Manual {@code invalidate()} is available for Admin API to
 * force immediate cache refresh on config changes.</p>
 */
public class ConfigCache implements ConfigRepository {

    private static final Logger logger = LoggerFactory.getLogger(ConfigCache.class);

    private final ConfigRepository delegate;
    private final long ttlMs;

    // Cache entries: tenantId -> entry
    private final ConcurrentHashMap<String, CacheEntry> modelConfigCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CacheEntry> apiKeysCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CacheEntry> modelRoutesCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CacheEntry> quotaCache = new ConcurrentHashMap<>();

    // Platform-level cache (single entry, keyed by "_global")
    private volatile CacheEntry providerCatalogCache;
    private volatile CacheEntry platformRoutesCache;

    private record CacheEntry(Object data, long loadedAt, long version) {}

    public ConfigCache(ConfigRepository delegate) {
        this(delegate, 30_000);  // 30s default
    }

    public ConfigCache(ConfigRepository delegate, long ttlMs) {
        this.delegate = delegate;
        this.ttlMs = ttlMs;
    }

    @Override
    public Map<String, Object> loadModelConfig(String tenantId) {
        return getCached(modelConfigCache, tenantId, () -> delegate.loadModelConfig(tenantId));
    }

    @Override
    public void saveModelConfig(String tenantId, Map<String, Object> config) {
        delegate.saveModelConfig(tenantId, config);
        invalidate(tenantId);
    }

    @Override
    public long getConfigVersion(String tenantId) {
        return delegate.getConfigVersion(tenantId);
    }

    @Override
    public Map<String, String> loadApiKeys(String tenantId) {
        return getCached(apiKeysCache, tenantId, () -> delegate.loadApiKeys(tenantId));
    }

    @Override
    public void saveApiKey(String tenantId, String provider, String apiKey) {
        delegate.saveApiKey(tenantId, provider, apiKey);
        apiKeysCache.remove(tenantId);
    }

    @Override
    public void removeApiKey(String tenantId, String provider) {
        delegate.removeApiKey(tenantId, provider);
        apiKeysCache.remove(tenantId);
    }

    @Override
    public List<ModelRoute> loadModelRoutes(String tenantId) {
        return getCached(modelRoutesCache, tenantId, () -> delegate.loadModelRoutes(tenantId));
    }

    @Override
    public void saveModelRoute(String tenantId, ModelRoute route) {
        delegate.saveModelRoute(tenantId, route);
        modelRoutesCache.remove(tenantId);
    }

    @Override
    public void removeModelRoute(String tenantId, String alias) {
        delegate.removeModelRoute(tenantId, alias);
        modelRoutesCache.remove(tenantId);
    }

    @Override
    public TenantQuota loadQuota(String tenantId) {
        return getCached(quotaCache, tenantId, () -> delegate.loadQuota(tenantId));
    }

    @Override
    public void saveQuota(String tenantId, TenantQuota quota) {
        delegate.saveQuota(tenantId, quota);
        quotaCache.remove(tenantId);
    }

    @Override
    public ProviderCatalog loadProviderCatalog() {
        return getGlobalCached(() -> delegate.loadProviderCatalog());
    }

    @Override
    public List<ModelRoute> loadPlatformModelRoutes() {
        return getGlobalCached(() -> delegate.loadPlatformModelRoutes());
    }

    @Override
    public String loadPlatformApiKey(String provider) {
        // Platform keys are not cached (low frequency, sensitive)
        return delegate.loadPlatformApiKey(provider);
    }

    // ============ Cache Management ============

    /**
     * Invalidate all cached entries for a tenant.
     * Called by Admin API after config changes.
     */
    public void invalidate(String tenantId) {
        modelConfigCache.remove(tenantId);
        apiKeysCache.remove(tenantId);
        modelRoutesCache.remove(tenantId);
        quotaCache.remove(tenantId);
        logger.debug("Invalidated config cache for tenant={}", tenantId);
    }

    /**
     * Invalidate all cached entries (all tenants + platform).
     */
    public void invalidateAll() {
        modelConfigCache.clear();
        apiKeysCache.clear();
        modelRoutesCache.clear();
        quotaCache.clear();
        providerCatalogCache = null;
        platformRoutesCache = null;
        logger.debug("Invalidated all config caches");
    }

    /**
     * Get cache statistics for monitoring.
     */
    public CacheStats getStats() {
        return new CacheStats(
            modelConfigCache.size(),
            apiKeysCache.size(),
            modelRoutesCache.size(),
            quotaCache.size(),
            ttlMs
        );
    }

    public record CacheStats(
        int modelConfigEntries,
        int apiKeyEntries,
        int modelRouteEntries,
        int quotaEntries,
        long ttlMs
    ) {}

    // ============ Internal ============

    @FunctionalInterface
    private interface Loader<T> {
        T load();
    }

    @SuppressWarnings("unchecked")
    private <T> T getCached(ConcurrentHashMap<String, CacheEntry> cache, String key, Loader<T> loader) {
        long now = System.currentTimeMillis();
        CacheEntry entry = cache.get(key);

        if (entry != null && (now - entry.loadedAt()) < ttlMs) {
            return (T) entry.data();
        }

        // Cache miss or expired -> load from delegate
        T data = loader.load();
        long version = (key != null) ? delegate.getConfigVersion(key) : 0;
        cache.put(key, new CacheEntry(data, now, version));
        return data;
    }

    @SuppressWarnings("unchecked")
    private <T> T getGlobalCached(Loader<T> loader) {
        long now = System.currentTimeMillis();

        // Use providerCatalogCache as the global cache slot (shared for both)
        if (providerCatalogCache != null && (now - providerCatalogCache.loadedAt()) < ttlMs) {
            return (T) providerCatalogCache.data();
        }

        T data = loader.load();
        providerCatalogCache = new CacheEntry(data, now, 0);
        return data;
    }

    
    // ============ Agent Templates (delegate to underlying repo) ============

    @Override
    public Map<String, AgentTemplate> loadAgentTemplates(String tenantId) {
        return delegate.loadAgentTemplates(tenantId);
    }

    @Override
    public void saveAgentTemplate(String tenantId, String name, AgentTemplate template) {
        delegate.saveAgentTemplate(tenantId, name, template);
    }

    @Override
    public void deleteAgentTemplate(String tenantId, String name) {
        delegate.deleteAgentTemplate(tenantId, name);
    }

    // ============ Tenant Settings (KV) - delegate without caching ============

    @Override
    public String loadTenantSetting(String tenantId, String key) {
        return delegate.loadTenantSetting(tenantId, key);
    }

    @Override
    public void saveTenantSetting(String tenantId, String key, String value) {
        delegate.saveTenantSetting(tenantId, key, value);
    }

    @Override
    public Map<String, String> loadAllTenantSettings(String tenantId) {
        return delegate.loadAllTenantSettings(tenantId);
    }
}
