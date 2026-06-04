package com.nousresearch.hermes.plugin.registry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Platform adapter registry.
 * Mirrors gateway/platform_registry.py PlatformRegistry.
 */
public class PlatformRegistry implements Registry<String, PlatformEntry> {
    private static final Logger logger = LoggerFactory.getLogger(PlatformRegistry.class);
    private final Map<String, PlatformEntry> entries = new ConcurrentHashMap<>();

    @Override
    public void register(String key, PlatformEntry entry) {
        PlatformEntry prev = entries.put(key, entry);
        if (prev != null) {
            logger.info("Platform '{}' re-registered (was {}, now {})",
                    key, prev.getSource(), entry.getSource());
        } else {
            logger.debug("Registered platform adapter: {} ({})", key, entry.getSource());
        }
    }

    @Override
    public void unregister(String key) {
        entries.remove(key);
    }

    @Override
    public Optional<PlatformEntry> get(String key) {
        return Optional.ofNullable(entries.get(key));
    }

    @Override
    public List<PlatformEntry> listAll() {
        return List.copyOf(entries.values());
    }

    @Override
    public boolean isRegistered(String key) {
        return entries.containsKey(key);
    }

    public List<PlatformEntry> listPluginEntries() {
        return entries.values().stream()
                .filter(e -> "plugin".equals(e.getSource()))
                .toList();
    }

    /**
     * Create an adapter instance if the entry passes checks.
     */
    public Optional<Object> createAdapter(String name, Object config) {
        PlatformEntry entry = entries.get(name);
        if (entry == null) return Optional.empty();

        if (!entry.getCheckFn().get()) {
            String hint = entry.getInstallHint() != null && !entry.getInstallHint().isEmpty()
                    ? " (" + entry.getInstallHint() + ")" : "";
            logger.warn("Platform '{}' requirements not met{}", entry.getLabel(), hint);
            return Optional.empty();
        }

        if (entry.getValidateConfig() != null && !entry.getValidateConfig().test(config)) {
            logger.warn("Platform '{}' config validation failed", entry.getLabel());
            return Optional.empty();
        }

        try {
            Object adapter = entry.getAdapterFactory().apply(config);
            return Optional.of(adapter);
        } catch (Exception e) {
            logger.error("Failed to create adapter for platform '{}': {}", entry.getLabel(), e.getMessage(), e);
            return Optional.empty();
        }
    }
}
