package com.nousresearch.hermes.harness;

import com.nousresearch.hermes.config.repository.ConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tenant-scoped registry for custom {@link AgentTemplate}s.
 *
 * <p>Allows tenants to define their own specialist agents (e.g. "HR Onboarding
 * Expert", "Financial Audit Reviewer") without code changes. Templates are
 * loaded from the tenant's config repository (MySQL in cluster mode, file in
 * local mode) and cached in memory.</p>
 *
 * <h2>Lookup order</h2>
 * <ol>
 *   <li>Tenant custom templates (from this registry)</li>
 *   <li>Built-in templates (from {@link AgentTemplate#find(String)})</li>
 * </ol>
 *
 * <h2>Template fields</h2>
 * <pre>
 * name: hr_onboarding_expert
 * description: HR 入职流程专家
 * system_prompt: |
 *   You are an HR onboarding specialist...
 * tool_whitelist: [read_file, write_file, web_search, execute_command]
 * max_iterations: 15
 * fork_mode: FULL
 * </pre>
 */
public class TenantAgentTemplateRegistry {

    private static final Logger logger = LoggerFactory.getLogger(TenantAgentTemplateRegistry.class);

    private final String tenantId;
    private final ConfigRepository configRepo;
    private final Map<String, AgentTemplate> cache = new ConcurrentHashMap<>();
    private volatile long lastLoadTime = 0;
    private static final long CACHE_TTL_MS = 60_000; // 1 minute

    public TenantAgentTemplateRegistry(String tenantId, ConfigRepository configRepo) {
        this.tenantId = tenantId;
        this.configRepo = configRepo;
    }

    /**
     * Find a template by name, checking tenant registry first, then built-ins.
     */
    public AgentTemplate find(String name) {
        if (name == null || name.isBlank()) return null;
        String key = name.toLowerCase().trim();

        // Check cache (with TTL refresh)
        ensureLoaded();
        AgentTemplate cached = cache.get(key);
        if (cached != null) return cached;

        // Fall back to built-in
        return AgentTemplate.find(key);
    }

    /**
     * List all available templates (tenant + built-in).
     */
    public Set<String> availableTemplates() {
        ensureLoaded();
        Set<String> all = new LinkedHashSet<>(cache.keySet());
        all.addAll(AgentTemplate.availableTemplates());
        return all;
    }

    /**
     * Register or update a custom template in the tenant's config repository.
     */
    public void register(AgentTemplate template) {
        if (template == null || template.name() == null || template.name().isBlank()) {
            throw new IllegalArgumentException("Template name is required");
        }
        String key = template.name().toLowerCase().trim();

        // Persist to config repo
        if (configRepo != null) {
            configRepo.saveAgentTemplate(tenantId, template.name(), template);
        }

        // Update cache
        cache.put(key, template);
        logger.info("Registered tenant template '{}' for tenant {}", template.name(), tenantId);
    }

    /**
     * Remove a custom template.
     */
    public boolean unregister(String name) {
        if (name == null) return false;
        String key = name.toLowerCase().trim();

        boolean removed = cache.remove(key) != null;
        if (removed && configRepo != null) {
            configRepo.deleteAgentTemplate(tenantId, name);
        }
        return removed;
    }

    /**
     * Force a reload from the config repository.
     */
    public void reload() {
        lastLoadTime = 0;
        ensureLoaded();
    }

    private void ensureLoaded() {
        if (System.currentTimeMillis() - lastLoadTime < CACHE_TTL_MS) return;
        lastLoadTime = System.currentTimeMillis();

        if (configRepo == null) return;

        try {
            Map<String, AgentTemplate> loaded = configRepo.loadAgentTemplates(tenantId);
            if (loaded != null && !loaded.isEmpty()) {
                cache.clear();
                for (var entry : loaded.entrySet()) {
                    cache.put(entry.getKey().toLowerCase().trim(), entry.getValue());
                }
                logger.debug("Loaded {} tenant templates for {}", cache.size(), tenantId);
            }
        } catch (Exception e) {
            logger.warn("Failed to load tenant templates: {}", e.getMessage());
        }
    }
}
