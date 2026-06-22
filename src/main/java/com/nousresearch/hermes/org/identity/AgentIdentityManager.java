package com.nousresearch.hermes.org.identity;

import com.nousresearch.hermes.collaboration.AgentRuntimeProfile;
import org.slf4j.Logger;
import com.nousresearch.hermes.org.OrgUtils;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central manager for all agent identities in the organization.
 *
 * <p>Handles identity lifecycle (provision, rotate, revoke, deactivate),
 * credential verification, and identity discovery. Integrates with
 * the org's SSO/OIDC provider for enterprise identity binding.</p>
 */
public class AgentIdentityManager {
    private static final Logger logger = LoggerFactory.getLogger(AgentIdentityManager.class);

    /** All identities keyed by agent ID. */
    private final ConcurrentHashMap<String, AgentIdentity> identities = new ConcurrentHashMap<>();

    /** Active API key hash → agent ID index for fast verification. */
    private final ConcurrentHashMap<String, String> keyIndex = new ConcurrentHashMap<>();

    /** Default credential validity period (90 days). */
    private static final long DEFAULT_CREDENTIAL_TTL_DAYS = 90;

    /** OIDC provider configuration. */
    private OidcConfig oidcConfig;

    public AgentIdentityManager() {}

    public AgentIdentityManager(OidcConfig oidcConfig) {
        this.oidcConfig = oidcConfig;
    }

    // ---- identity lifecycle ----

    /**
     * Provision a new agent identity with API key credentials.
     *
     * @param agentId unique identifier for this agent
     * @param displayName human-readable name
     * @param role agent's organizational role
     * @return the newly created identity
     */
    public AgentIdentity provision(String agentId, String displayName, AgentRuntimeProfile role) {
        if (identities.containsKey(agentId)) {
            throw new IllegalArgumentException("Agent identity already exists: " + agentId);
        }

        AgentIdentity identity = new AgentIdentity(agentId, displayName, role);

        // Issue initial API key credential
        Instant expiry = Instant.now().plusSeconds(DEFAULT_CREDENTIAL_TTL_DAYS * 86400);
        AgentCredential apiKey = AgentCredential.generateApiKey(expiry);
        identity.issueCredential(apiKey);

        // Bind OIDC if configured
        if (oidcConfig != null) {
            identity.bindOidc(oidcConfig.url, oidcConfig.clientId, agentId);
        }

        identities.put(agentId, identity);
        rebuildKeyIndex();

        logger.info("Provisioned agent identity: {} ({})", agentId, displayName);
        return identity;
    }

    /**
     * Rotate credentials for a given agent.
     * Revokes the current signing key and issues a fresh one.
     */
    public AgentCredential rotateCredentials(String agentId, String operator, String reason) {
        AgentIdentity identity = requireActive(agentId);
        AgentCredential oldSigning = identity.getSigningCredential();
        if (oldSigning == null) {
            // No existing signing credential — issue new one
            Instant expiry = Instant.now().plusSeconds(DEFAULT_CREDENTIAL_TTL_DAYS * 86400);
            AgentCredential fresh = AgentCredential.generateApiKey(expiry);
            identity.issueCredential(fresh);
            rebuildKeyIndex();
            logger.info("Issued new signing credential for {} by {}", agentId, operator);
            return fresh;
        }
        AgentCredential fresh = identity.rotateCredential(oldSigning.getId(), operator, reason);
        rebuildKeyIndex();
        logger.info("Rotated credential for {} by {}", agentId, operator);
        return fresh;
    }

    /** Deactivate an agent identity (revokes all credentials). */
    public void deactivate(String agentId, String operator, String reason) {
        AgentIdentity identity = requireActive(agentId);
        identity.deactivate(operator, reason);
        rebuildKeyIndex();
        logger.warn("Deactivated agent identity: {} by {}: {}", agentId, operator, reason);
    }

    // ---- verification ----

    /**
     * Authenticate a request using a raw API key value.
     * Returns the authenticated agent identity, or empty if invalid.
     */
    public Optional<AgentIdentity> authenticateByApiKey(String rawApiKey) {
        // Hash the incoming key for lookup
        String keyHash = OrgUtils.sha256(rawApiKey);
        String agentId = keyIndex.get(keyHash);
        if (agentId == null) return Optional.empty();

        AgentIdentity identity = identities.get(agentId);
        if (identity == null || !identity.isActive()) return Optional.empty();

        if (identity.verifyCredential(keyHash)) {
            return Optional.of(identity);
        }
        return Optional.empty();
    }

    /**
     * Verify a signed message from an agent.
     */
    public boolean verifySignedMessage(String agentId, String messageBody, String nonce, String signature) {
        AgentIdentity identity = identities.get(agentId);
        if (identity == null || !identity.isActive()) return false;
        try {
            String expected = identity.signMessage(messageBody, nonce);
            return java.security.MessageDigest.isEqual(
                expected.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                signature.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }

    // ---- discovery ----

    /** List all active agent identities. */
    public List<AgentIdentity> listActive() {
        return identities.values().stream()
            .filter(AgentIdentity::isActive)
            .toList();
    }

    /** Find agents by role name. */
    public List<AgentIdentity> findByRole(String roleName) {
        return identities.values().stream()
            .filter(i -> i.getRole().getRoleName().equalsIgnoreCase(roleName))
            .toList();
    }

    /** Find agents by department. */
    public List<AgentIdentity> findByDepartment(String department) {
        return identities.values().stream()
            .filter(i -> department.equals(i.getDepartment()))
            .toList();
    }

    /** Find agents by tag. */
    public List<AgentIdentity> findByTag(String key, String value) {
        return identities.values().stream()
            .filter(i -> value.equals(i.getTags().get(key)))
            .toList();
    }

    /** Get identity by ID. */
    public Optional<AgentIdentity> get(String agentId) {
        return Optional.ofNullable(identities.get(agentId));
    }

    // ---- health -------

    /** Collect warnings from all identities. */
    public Map<String, List<String>> getOrganizationalWarnings() {
        Map<String, List<String>> warnings = new LinkedHashMap<>();
        for (AgentIdentity id : identities.values()) {
            List<String> w = id.getWarnings();
            if (!w.isEmpty()) warnings.put(id.getAgentId(), w);
        }
        return warnings;
    }

    /** Summary statistics for the org dashboard. */
    public Map<String, Object> getSummary() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("total_identities", identities.size());
        s.put("active", listActive().size());
        s.put("deactivated", identities.size() - listActive().size());
        s.put("with_oidc", identities.values().stream().filter(i -> i.getOidcBinding().isPresent()).count());
        s.put("expiring_credentials", identities.values().stream()
            .flatMap(i -> i.getCredentials().values().stream())
            .filter(c -> c.getStatus() == AgentCredential.Status.EXPIRING).count());
        return s;
    }

    // ---- internal ----

    private AgentIdentity requireActive(String agentId) {
        AgentIdentity identity = identities.get(agentId);
        if (identity == null) throw new IllegalArgumentException("Unknown agent: " + agentId);
        if (!identity.isActive()) throw new IllegalStateException("Agent is deactivated: " + agentId);
        return identity;
    }

    private void rebuildKeyIndex() {
        keyIndex.clear();
        for (AgentIdentity id : identities.values()) {
            if (!id.isActive()) continue;
            for (AgentCredential c : id.getCredentials().values()) {
                if (c.getStatus() == AgentCredential.Status.ACTIVE
                    || c.getStatus() == AgentCredential.Status.EXPIRING) {
                    keyIndex.put(c.getHash(), id.getAgentId());
                }
            }
        }
    }



    /** OIDC provider configuration. */
    public record OidcConfig(String url, String clientId, String clientSecret) {}
}
