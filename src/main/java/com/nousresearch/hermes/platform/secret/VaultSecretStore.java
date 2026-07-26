package com.nousresearch.hermes.platform.secret;

/**
 * B7: Vault SecretStore stub.
 *
 * <p>Placeholder for HashiCorp Vault integration. When implemented, this will
 * use the Vault Java client to read/write secrets at path
 * {@code secret/hermes/tenants/{tenantId}/{key}}.</p>
 *
 * <p>Implementation requires adding {@code io.github.jopenlibs:vault-java-driver}
 * as a Maven dependency. The stub throws UnsupportedOperationException to
 * make it clear that Vault is not yet wired.</p>
 *
 * <p>Wiring steps (when ready):</p>
 * <ol>
 *   <li>Add {@code vault-java-driver} dependency to pom.xml</li>
 *   <li>Configure Vault connection: vault.address, vault.token, vault.path-prefix</li>
 *   <li>Implement getSecret/setSecret/removeSecret using Vault REST API</li>
 *   <li>Set {@code TenantConfig.setSharedSecretStore(new VaultSecretStore(...))}</li>
 * </ol>
 */
public class VaultSecretStore implements SecretStore {

    private final String vaultAddress;
    private final String vaultToken;
    private final String pathPrefix;

    public VaultSecretStore(String vaultAddress, String vaultToken, String pathPrefix) {
        this.vaultAddress = vaultAddress;
        this.vaultToken = vaultToken;
        this.pathPrefix = pathPrefix != null ? pathPrefix : "secret/hermes/tenants";
    }

    @Override
    public String getSecret(String tenantId, String key) {
        // TODO: Implement with Vault REST API
        // GET {vaultAddress}/v1/{pathPrefix}/{tenantId}/{key}
        // Header: X-Vault-Token: {vaultToken}
        throw new UnsupportedOperationException(
            "VaultSecretStore not yet implemented. Use FileSecretStore or InMemorySecretStore.");
    }

    @Override
    public void setSecret(String tenantId, String key, String value) {
        // TODO: Implement with Vault REST API
        // POST {vaultAddress}/v1/{pathPrefix}/{tenantId}/{key}
        // Body: {"value": value}
        throw new UnsupportedOperationException(
            "VaultSecretStore not yet implemented.");
    }

    @Override
    public boolean removeSecret(String tenantId, String key) {
        // TODO: DELETE {vaultAddress}/v1/{pathPrefix}/{tenantId}/{key}
        throw new UnsupportedOperationException(
            "VaultSecretStore not yet implemented.");
    }

    @Override
    public java.util.Set<String> listSecrets(String tenantId) {
        // TODO: LIST {vaultAddress}/v1/{pathPrefix}/{tenantId}
        throw new UnsupportedOperationException(
            "VaultSecretStore not yet implemented.");
    }

    @Override
    public boolean hasSecret(String tenantId, String key) {
        // Can implement as try-catch on getSecret once implemented
        throw new UnsupportedOperationException(
            "VaultSecretStore not yet implemented.");
    }

    @Override
    public java.util.Map<String, String> loadAll(String tenantId) {
        // TODO: LIST + GET each key
        throw new UnsupportedOperationException(
            "VaultSecretStore not yet implemented.");
    }

    public String getVaultAddress() { return vaultAddress; }
    public String getPathPrefix() { return pathPrefix; }
}
