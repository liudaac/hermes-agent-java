package com.nousresearch.hermes.org.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Unified permission engine combining RBAC and ABAC.
 *
 * <p>Evaluates authorization in three stages:
 * <ol>
 *   <li><b>RBAC check</b> — Does the subject have the role/permission?</li>
 *   <li><b>ABAC check</b> — Do the contextual attributes allow it?</li>
 *   <li><b>Override</b> — Any explicit approval or override?</li>
 * </ol>
 *
 * <p>Deny is the default. Access is granted only when both RBAC and
 * ABAC checks pass, or an active override exists.</p>
 */
public class PermissionPolicy {
    private static final Logger logger = LoggerFactory.getLogger(PermissionPolicy.class);

    private final RoleBasedAccessControl rbac;
    private final AttributeBasedAccessControl abac;

    /** Explicit overrides: subjectId → set of permissions to always allow. */
    private final Map<String, Set<String>> overrides = new LinkedHashMap<>();

    public PermissionPolicy() {
        this.rbac = new RoleBasedAccessControl();
        this.abac = new AttributeBasedAccessControl();
    }

    public PermissionPolicy(RoleBasedAccessControl rbac, AttributeBasedAccessControl abac) {
        this.rbac = rbac;
        this.abac = abac;
    }

    // ---- delegate accessors ----

    public RoleBasedAccessControl rbac() { return rbac; }
    public AttributeBasedAccessControl abac() { return abac; }

    // ---- override management ----

    /** Grant an explicit override (temporary or permanent). */
    public void grantOverride(String subjectId, String permission) {
        overrides.computeIfAbsent(subjectId, k -> new LinkedHashSet<>()).add(permission);
    }

    /** Revoke an override. */
    public void revokeOverride(String subjectId, String permission) {
        Set<String> s = overrides.get(subjectId);
        if (s != null) s.remove(permission);
    }

    /** Clear all overrides for a subject. */
    public void clearOverrides(String subjectId) {
        overrides.remove(subjectId);
    }

    // ---- authorization -------

    /**
     * Full combined authorization check.
     */
    public PermissionResult authorize(
            String subjectId,
            String permission,
            String resource,
            Map<String, Object> subjectAttrs,
            Map<String, Object> resourceAttrs,
            Map<String, Object> environment) {

        // 1. Check overrides (explicit allow)
        Set<String> subjectOverrides = overrides.getOrDefault(subjectId, Set.of());
        if (subjectOverrides.contains(permission) || subjectOverrides.contains("*")) {
            return PermissionResult.allow("Override", "Explicit override granted");
        }

        // 2. RBAC check
        if (!rbac.checkPermission(subjectId, permission)) {
            String reason = String.format("RBAC: subject '%s' lacks permission '%s'", subjectId, permission);
            logger.debug(reason);
            return PermissionResult.deny("RBAC", reason);
        }

        // 3. ABAC context check
        AttributeBasedAccessControl.AuthContext abacCtx = new AttributeBasedAccessControl.AuthContext(
            subjectId, subjectAttrs, resource, resourceAttrs, permission, environment);

        AttributeBasedAccessControl.AuthDecision decision = abac.evaluate(abacCtx);
        if (!decision.allowed()) {
            return PermissionResult.deny("ABAC", decision.reason());
        }

        return PermissionResult.allow("RBAC+ABAC", decision.reason());
    }

    /**
     * Simple permission check — RBAC + overrides, no ABAC.
     */
    public boolean check(String subjectId, String permission) {
        // Check overrides first
        Set<String> subjectOverrides = overrides.getOrDefault(subjectId, Set.of());
        if (subjectOverrides.contains(permission) || subjectOverrides.contains("*")) {
            return true;
        }
        return rbac.checkPermission(subjectId, permission);
    }

    /** Permission result with provenance. */
    public record PermissionResult(boolean allowed, String source, String reason) {
        public static PermissionResult allow(String source, String reason) {
            return new PermissionResult(true, source, reason);
        }
        public static PermissionResult deny(String source, String reason) {
            return new PermissionResult(false, source, reason);
        }
    }

    // ---- batch check -------

    /**
     * Check multiple permissions at once, returning results keyed by permission.
     */
    public Map<String, PermissionResult> authorizeBatch(
            String subjectId,
            List<String> permissions,
            String resource,
            Map<String, Object> subjectAttrs,
            Map<String, Object> resourceAttrs,
            Map<String, Object> environment) {

        Map<String, PermissionResult> results = new LinkedHashMap<>();
        for (String perm : permissions) {
            results.put(perm, authorize(subjectId, perm, resource, subjectAttrs, resourceAttrs, environment));
        }
        return results;
    }

    /** Summary for dashboard. */
    public Map<String, Object> getSummary() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("rbac", rbac.getSummary());
        s.put("abac_policies", abac.policyCount());
        s.put("overrides", overrides.size());
        return s;
    }
}
