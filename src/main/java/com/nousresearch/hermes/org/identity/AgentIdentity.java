package com.nousresearch.hermes.org.identity;

import com.nousresearch.hermes.collaboration.AgentRole;

import com.nousresearch.hermes.org.OrgUtils;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Full identity of an AI agent as an organizational member.
 *
 * <p>An AgentIdentity wraps an AgentRole with verified credentials,
 * SSO binding, and signing capabilities. This is what transforms a
 * tenant agent from an anonymous worker into a recognized org member
 * with verifiable outputs.</p>
 */
public class AgentIdentity {

    /** Stable unique identifier for this agent across its lifetime. */
    private final String agentId;

    /** Human-readable display name (e.g. "ReleaseBot v2"). */
    private final String displayName;

    /** Organizational role (capabilities + reporting structure). */
    private final AgentRole role;

    /** Department / team the agent belongs to. */
    private String department;

    /** Current active credentials, keyed by credential ID. */
    private final ConcurrentHashMap<String, AgentCredential> credentials = new ConcurrentHashMap<>();

    /** Credential ID used for signing outgoing messages. */
    private volatile String signingCredentialId;

    /** Organizational identity provider binding. */
    private OidcBinding oidcBinding;

    /** When this identity was provisioned. */
    private final Instant provisionedAt;

    /** Last successful authentication timestamp. */
    private volatile Instant lastAuthAt;

    /** Whether this agent identity is currently active. */
    private volatile boolean active = true;

    private volatile Instant deactivatedAt;
    private String deactivatedBy;

    /** Metadata tags for discovery. */
    private final Map<String, String> tags = new LinkedHashMap<>();

    public AgentIdentity(String agentId, String displayName, AgentRole role) {
        this.agentId = Objects.requireNonNull(agentId, "agentId");
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        this.role = Objects.requireNonNull(role, "role");
        this.provisionedAt = Instant.now();
    }

    // ---- credential management ----

    /** Issue a new credential to this agent. */
    public AgentCredential issueCredential(AgentCredential credential) {
        credentials.put(credential.getId(), credential);
        if (signingCredentialId == null && credential.getType() == AgentCredential.Type.API_KEY) {
            signingCredentialId = credential.getId();
        }
        return credential;
    }

    /** Rotate a credential: revoke old, issue new of same type. */
    public AgentCredential rotateCredential(String oldId, String revokedBy, String reason) {
        AgentCredential old = credentials.get(oldId);
        if (old == null) throw new IllegalArgumentException("credential not found: " + oldId);
        old.markRevoked(revokedBy, reason);
        AgentCredential fresh = switch (old.getType()) {
            case API_KEY -> AgentCredential.generateApiKey(old.getExpiresAt());
            case JWT -> throw new UnsupportedOperationException("JWT rotation requires CA");
            default -> throw new UnsupportedOperationException("auto-rotation not supported for " + old.getType());
        };
        credentials.put(fresh.getId(), fresh);
        if (Objects.equals(signingCredentialId, oldId)) {
            signingCredentialId = fresh.getId();
        }
        return fresh;
    }

    /** Revoke all credentials and deactivate the identity. */
    public void deactivate(String by, String reason) {
        this.active = false;
        this.deactivatedAt = Instant.now();
        this.deactivatedBy = by;
        for (AgentCredential c : credentials.values()) {
            if (c.getStatus() != AgentCredential.Status.REVOKED) {
                c.markRevoked(by, reason);
            }
        }
    }

    /** Verify that a presented credential belongs to this agent and is valid. */
    public boolean verifyCredential(String credentialHash) {
        if (!active) return false;
        for (AgentCredential c : credentials.values()) {
            if (c.getHash().equals(credentialHash)) {
                c.refreshStatus();
                if (c.getStatus() == AgentCredential.Status.ACTIVE) {
                    lastAuthAt = Instant.now();
                    return true;
                }
            }
        }
        return false;
    }

    /** Get the credential used for signing outgoing messages. */
    public AgentCredential getSigningCredential() {
        if (signingCredentialId == null) return null;
        return credentials.get(signingCredentialId);
    }

    /** Generate a nonce-signed assertion for an outgoing message. */
    public String signMessage(String messageBody, String nonce) {
        AgentCredential signer = getSigningCredential();
        if (signer == null) throw new IllegalStateException("No signing credential configured");
        String payload = agentId + ":" + nonce + ":" + messageBody;
        return OrgUtils.hmacSha256(signer.getValue(), payload);
    }

    // ---- SSO / OIDC ----

    public void bindOidc(String providerUrl, String clientId, String subjectClaim) {
        this.oidcBinding = new OidcBinding(providerUrl, clientId, subjectClaim);
    }

    public Optional<OidcBinding> getOidcBinding() { return Optional.ofNullable(oidcBinding); }

    // ---- status ----

    public boolean isActive() { return active; }

    /** Collect warnings: expiring credentials, inactive status, missing binding. */
    public List<String> getWarnings() {
        List<String> warnings = new ArrayList<>();
        if (!active) warnings.add("identity is deactivated");
        for (AgentCredential c : credentials.values()) {
            c.refreshStatus();
            if (c.getStatus() == AgentCredential.Status.EXPIRING) {
                warnings.add("credential " + c.getId() + " is expiring");
            } else if (c.getStatus() == AgentCredential.Status.EXPIRED) {
                warnings.add("credential " + c.getId() + " has expired");
            }
        }
        if (signingCredentialId == null) warnings.add("no signing credential configured");
        return warnings;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("agent_id", agentId);
        m.put("display_name", displayName);
        m.put("role", role.getRoleName());
        m.put("level", role.getLevel().name());
        m.put("department", department);
        m.put("active", active);
        m.put("provisioned_at", provisionedAt.toString());
        m.put("last_auth_at", lastAuthAt != null ? lastAuthAt.toString() : null);
        m.put("credential_count", credentials.size());
        m.put("oidc_bound", oidcBinding != null);
        m.put("tags", tags);
        m.put("warnings", getWarnings());
        return m;
    }

    // ---- fluent setters ----

    public AgentIdentity department(String dept) { this.department = dept; return this; }
    public AgentIdentity tag(String key, String value) { tags.put(key, value); return this; }

    // ---- getters ----

    public String getAgentId() { return agentId; }
    public String getDisplayName() { return displayName; }
    public AgentRole getRole() { return role; }
    public String getDepartment() { return department; }
    public Map<String, AgentCredential> getCredentials() { return Collections.unmodifiableMap(credentials); }
    public Instant getProvisionedAt() { return provisionedAt; }
    public Instant getLastAuthAt() { return lastAuthAt; }
    public Instant getDeactivatedAt() { return deactivatedAt; }
    public String getDeactivatedBy() { return deactivatedBy; }
    public Map<String, String> getTags() { return Collections.unmodifiableMap(tags); }

    /** OIDC binding record. */
    public record OidcBinding(String providerUrl, String clientId, String subjectClaim) {}

    // ---- internal ----


}
