package com.nousresearch.hermes.platform.secret;

/**
 * B7: Secret store abstraction for tenant API keys and credentials.
 *
 * <p>Abstraction layer that decouples secret storage from the file-based
 * {@code secrets.env} approach. Enables cloud deployments to use Vault,
 * AWS Secrets Manager, Alibaba Cloud KMS, etc. without changing
 * TenantConfig code.</p>
 *
 * <p>Implementations:</p>
 * <ul>
 *   <li>{@link FileSecretStore} - reads/writes secrets.env (default, single-instance)</li>
 *   <li>{@link VaultSecretStore} - HashiCorp Vault (cluster mode, TODO)</li>
 *   <li>Custom implementations via SPI</li>
 * </ul>
 *
 * @author Hermes Team
 * @version B7
 */
public interface SecretStore {

    /**
     * Retrieve a secret value by key.
     *
     * @param tenantId the tenant identifier
     * @param key      the secret key (e.g. "OPENAI_API_KEY")
     * @return the secret value, or null if not found
     */
    String getSecret(String tenantId, String key);

    /**
     * Store a secret value.
     *
     * @param tenantId the tenant identifier
     * @param key      the secret key
     * @param value    the secret value
     */
    void setSecret(String tenantId, String key, String value);

    /**
     * Remove a secret.
     *
     * @param tenantId the tenant identifier
     * @param key      the secret key
     * @return true if a secret was removed
     */
    boolean removeSecret(String tenantId, String key);

    /**
     * List all secret keys for a tenant (values not included for security).
     *
     * @param tenantId the tenant identifier
     * @return set of secret key names
     */
    java.util.Set<String> listSecrets(String tenantId);

    /**
     * Check if a secret exists.
     *
     * @param tenantId the tenant identifier
     * @param key      the secret key
     * @return true if the secret exists
     */
    boolean hasSecret(String tenantId, String key);

    /**
     * Bulk load all secrets for a tenant into a map.
     * Useful for migration and backup.
     *
     * @param tenantId the tenant identifier
     * @return map of key -> value (empty if none)
     */
    java.util.Map<String, String> loadAll(String tenantId);
}
