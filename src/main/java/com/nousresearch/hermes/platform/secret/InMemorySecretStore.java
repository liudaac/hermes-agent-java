package com.nousresearch.hermes.platform.secret;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * B7: In-memory SecretStore for testing and ephemeral deployments.
 *
 * <p>Does not persist to disk. Useful for unit tests and integration tests
 * where file I/O is undesirable, or for container deployments where
 * secrets are injected via environment variables at startup.</p>
 */
public class InMemorySecretStore implements SecretStore {

    private static final Logger logger = LoggerFactory.getLogger(InMemorySecretStore.class);

    private final Map<String, Map<String, String>> store = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public String getSecret(String tenantId, String key) {
        return store.getOrDefault(tenantId, Map.of()).get(key);
    }

    @Override
    public void setSecret(String tenantId, String key, String value) {
        store.computeIfAbsent(tenantId, k -> new java.util.concurrent.ConcurrentHashMap<>())
            .put(key, value);
    }

    @Override
    public boolean removeSecret(String tenantId, String key) {
        Map<String, String> tenantSecrets = store.get(tenantId);
        if (tenantSecrets == null) return false;
        return tenantSecrets.remove(key) != null;
    }

    @Override
    public Set<String> listSecrets(String tenantId) {
        return Collections.unmodifiableSet(
            new LinkedHashSet<>(store.getOrDefault(tenantId, Map.of()).keySet()));
    }

    @Override
    public boolean hasSecret(String tenantId, String key) {
        return store.getOrDefault(tenantId, Map.of()).containsKey(key);
    }

    @Override
    public Map<String, String> loadAll(String tenantId) {
        return Collections.unmodifiableMap(
            new LinkedHashMap<>(store.getOrDefault(tenantId, Map.of())));
    }

    /**
     * Pre-load secrets from environment variables matching a prefix pattern.
     * e.g. loadFromEnv("tenantA", "OPENAI_API_KEY") loads OPENAI_API_KEY env var.
     *
     * @param tenantId the tenant to load for
     * @param envKeys  environment variable names to load
     */
    public void loadFromEnv(String tenantId, String... envKeys) {
        for (String envKey : envKeys) {
            String value = System.getenv(envKey);
            if (value != null && !value.isBlank()) {
                setSecret(tenantId, envKey, value);
                logger.debug("Loaded env var {} for tenant {}", envKey, tenantId);
            }
        }
    }
}
