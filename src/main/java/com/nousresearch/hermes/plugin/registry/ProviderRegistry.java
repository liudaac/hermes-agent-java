package com.nousresearch.hermes.plugin.registry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Generic provider registry with built-in-first semantics.
 * Used for image_gen, browser, tts, transcription, web_search, video_gen backends.
 *
 * @param <T> provider type, must expose getName()
 */
public class ProviderRegistry<T extends NamedProvider> implements Registry<String, T> {
    private static final Logger logger = LoggerFactory.getLogger(ProviderRegistry.class);
    private final Map<String, T> providers = new ConcurrentHashMap<>();
    private final Set<String> builtinNames = ConcurrentHashMap.newKeySet();
    private final String category;

    public ProviderRegistry(String category) {
        this.category = category;
    }

    /**
     * Register a built-in provider (ships with hermes).
     * Built-ins always win on name collision.
     */
    public void registerBuiltin(T provider) {
        builtinNames.add(provider.getName());
        providers.put(provider.getName(), provider);
        logger.debug("Registered built-in {} provider: {}", category, provider.getName());
    }

    @Override
    public void register(String key, T entry) {
        if (builtinNames.contains(key)) {
            logger.warn("Plugin tried to override built-in {} provider '{}'; ignoring.", category, key);
            return;
        }
        providers.put(key, entry);
        logger.info("Registered {} provider: {}", category, key);
    }

    @Override
    public void unregister(String key) {
        providers.remove(key);
    }

    @Override
    public Optional<T> get(String key) {
        return Optional.ofNullable(providers.get(key));
    }

    @Override
    public List<T> listAll() {
        return List.copyOf(providers.values());
    }

    @Override
    public boolean isRegistered(String key) {
        return providers.containsKey(key);
    }

    /**
     * Resolve provider by config key (e.g. config "image_gen.provider").
     */
    public Optional<T> resolve(String name) {
        return get(name);
    }

    public String getCategory() {
        return category;
    }
}
