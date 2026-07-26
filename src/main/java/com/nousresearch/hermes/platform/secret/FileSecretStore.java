package com.nousresearch.hermes.platform.secret;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * B7: File-based SecretStore implementation.
 *
 * <p>Reads and writes secrets to {@code tenants/{tenantId}/config/secrets.env}
 * in standard {@code KEY=VALUE} format with {@code rw-------} permissions.</p>
 *
 * <p>This is the default implementation for single-instance (LOCAL mode) deployments.
 * For cluster mode, use {@link VaultSecretStore} (when available).</p>
 */
public class FileSecretStore implements SecretStore {

    private static final Logger logger = LoggerFactory.getLogger(FileSecretStore.class);
    private static final String SECRETS_FILE = "secrets.env";

    private final Path tenantsDir;

    // Cache: tenantId -> (key -> value)
    private final ConcurrentHashMap<String, Map<String, String>> cache = new ConcurrentHashMap<>();

    public FileSecretStore(Path tenantsDir) {
        this.tenantsDir = tenantsDir;
        try {
            Files.createDirectories(tenantsDir);
        } catch (IOException e) {
            logger.error("Failed to create tenants directory: {}", tenantsDir, e);
        }
    }

    @Override
    public String getSecret(String tenantId, String key) {
        return loadSecrets(tenantId).get(key);
    }

    @Override
    public void setSecret(String tenantId, String key, String value) {
        Map<String, String> secrets = loadSecrets(tenantId);
        secrets.put(key, value);
        persist(tenantId, secrets);
    }

    @Override
    public boolean removeSecret(String tenantId, String key) {
        Map<String, String> secrets = loadSecrets(tenantId);
        String removed = secrets.remove(key);
        if (removed != null) {
            persist(tenantId, secrets);
            return true;
        }
        return false;
    }

    @Override
    public Set<String> listSecrets(String tenantId) {
        return Collections.unmodifiableSet(loadSecrets(tenantId).keySet());
    }

    @Override
    public boolean hasSecret(String tenantId, String key) {
        return loadSecrets(tenantId).containsKey(key);
    }

    @Override
    public Map<String, String> loadAll(String tenantId) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(loadSecrets(tenantId)));
    }

    // ============ Internal ============

    private Path secretsFile(String tenantId) {
        return tenantsDir.resolve(sanitize(tenantId)).resolve("config").resolve(SECRETS_FILE);
    }

    private Map<String, String> loadSecrets(String tenantId) {
        return cache.computeIfAbsent(tenantId, this::readFromDisk);
    }

    private Map<String, String> readFromDisk(String tenantId) {
        Map<String, String> result = new LinkedHashMap<>();
        Path file = secretsFile(tenantId);

        if (!Files.exists(file)) {
            return result;
        }

        try {
            List<String> lines = Files.readAllLines(file);
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq > 0) {
                    result.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
                }
            }
        } catch (IOException e) {
            logger.error("Failed to load secrets for tenant {}: {}", tenantId, e.getMessage());
        }
        return result;
    }

    private void persist(String tenantId, Map<String, String> secrets) {
        Path file = secretsFile(tenantId);
        try {
            Files.createDirectories(file.getParent());
            StringBuilder sb = new StringBuilder();
            sb.append("# Tenant Secrets\n");
            sb.append("# WARNING: Keep this file secure!\n\n");
            for (Map.Entry<String, String> e : secrets.entrySet()) {
                sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
            }
            Files.writeString(file, sb.toString());

            // Set permissions (Unix)
            if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                Set<java.nio.file.attribute.PosixFilePermission> perms =
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------");
                Files.setPosixFilePermissions(file, perms);
            }
        } catch (IOException e) {
            logger.error("Failed to persist secrets for tenant {}: {}", tenantId, e.getMessage());
        }
    }

    private static String sanitize(String tenantId) {
        return tenantId.replaceAll("[^\\p{L}\\p{N}_-]", "_").toLowerCase();
    }
}
