package com.nousresearch.hermes.org.identity;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import com.nousresearch.hermes.org.OrgUtils;
import java.util.Objects;

/**
 * Agent credential with type, value, and lifecycle management.
 * Supports API keys, OAuth tokens, and mTLS certificates.
 */
public class AgentCredential {

    public enum Type {
        /** HMAC-signed API key for internal service calls */
        API_KEY,
        /** OAuth 2.0 Bearer token, auto-refreshed */
        OAUTH_TOKEN,
        /** mTLS client certificate */
        TLS_CERTIFICATE,
        /** JWT signed by org CA */
        JWT
    }

    public enum Status { ACTIVE, EXPIRING, EXPIRED, REVOKED }

    private final String id;
    private final Type type;
    private final String value;
    private final String hash;       // SHA-256 of value for safe logging
    private final Instant issuedAt;
    private final Instant expiresAt;
    private volatile Status status;
    private Instant revokedAt;
    private String revokedBy;
    private String revocationReason;

    private static final SecureRandom RNG = new SecureRandom();

    private AgentCredential(Builder builder) {
        this.id = builder.id != null ? builder.id : genId();
        this.type = Objects.requireNonNull(builder.type, "credential type");
        this.value = Objects.requireNonNull(builder.value, "credential value");
        this.hash = OrgUtils.sha256(value);
        this.issuedAt = builder.issuedAt != null ? builder.issuedAt : Instant.now();
        this.expiresAt = builder.expiresAt;
        this.status = Status.ACTIVE;
    }

    // ---- factory methods ----

    /** Generate a new HMAC API key (256-bit random, base64url). */
    public static AgentCredential generateApiKey(Instant expiresAt) {
        byte[] key = new byte[32];
        RNG.nextBytes(key);
        String value = "hak_" + Base64.getUrlEncoder().withoutPadding().encodeToString(key);
        return new Builder(Type.API_KEY, value).expiresAt(expiresAt).build();
    }

    /** Generate a new JWT signed by the org CA. */
    public static AgentCredential generateJwt(String signedToken, Instant expiresAt) {
        return new Builder(Type.JWT, signedToken).expiresAt(expiresAt).build();
    }

    /** Wrap an existing OAuth token. */
    public static AgentCredential fromOAuthToken(String accessToken, Instant expiresAt) {
        return new Builder(Type.OAUTH_TOKEN, accessToken).expiresAt(expiresAt).build();
    }

    /** Register a TLS client certificate. */
    public static AgentCredential fromTlsCertificate(String pemCertificate, Instant expiresAt) {
        return new Builder(Type.TLS_CERTIFICATE, pemCertificate).expiresAt(expiresAt).build();
    }

    // ---- lifecycle ----

    public boolean isExpired() { return expiresAt != null && Instant.now().isAfter(expiresAt); }

    public boolean isExpiringSoon() {
        if (expiresAt == null) return false;
        return Instant.now().plusSeconds(86400).isAfter(expiresAt); // 24h
    }

    public void markRevoked(String by, String reason) {
        this.status = Status.REVOKED;
        this.revokedAt = Instant.now();
        this.revokedBy = by;
        this.revocationReason = reason;
    }

    /** Re-check and update status based on expiry. */
    public Status refreshStatus() {
        if (status == Status.REVOKED) return status;
        if (isExpired()) { status = Status.EXPIRED; return status; }
        if (isExpiringSoon()) { status = Status.EXPIRING; return status; }
        status = Status.ACTIVE;
        return status;
    }

    /** Safe representation for logging (only shows hash, never raw value). */
    public String toSafeString() {
        return String.format("Credential[%s type=%s issued=%s expires=%s]", id, type, issuedAt, expiresAt);
    }

    // ---- getters ----
    public String getId() { return id; }
    public Type getType() { return type; }
    public String getValue() { return value; }
    public String getHash() { return hash; }
    public Instant getIssuedAt() { return issuedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Status getStatus() { return status; }
    public Instant getRevokedAt() { return revokedAt; }
    public String getRevokedBy() { return revokedBy; }
    public String getRevocationReason() { return revocationReason; }

    // ---- internal ----



    private static String genId() {
        byte[] b = new byte[12];
        RNG.nextBytes(b);
        return "cred_" + Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    public static class Builder {
        private String id;
        private final Type type;
        private final String value;
        private Instant issuedAt;
        private Instant expiresAt;

        public Builder(Type type, String value) { this.type = type; this.value = value; }
        public Builder id(String id) { this.id = id; return this; }
        public Builder issuedAt(Instant t) { this.issuedAt = t; return this; }
        public Builder expiresAt(Instant t) { this.expiresAt = t; return this; }
        public AgentCredential build() { return new AgentCredential(this); }
    }
}
