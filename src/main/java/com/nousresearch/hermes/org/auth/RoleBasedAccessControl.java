package com.nousresearch.hermes.org.auth;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Role-Based Access Control (RBAC) for AI agents and human operators.
 *
 * <p>Defines what each role can do:
 * <ul>
 *   <li><b>Permissions</b> — atomic actions (e.g. "file:read", "code:execute")</li>
 *   <li><b>Roles</b> — named collections of permissions</li>
 *   <li><b>Assignments</b> — mapping agents/users to roles within scopes</li>
 * </ul>
 */
public class RoleBasedAccessControl {

    // ---- permission catalog ----

    /** Well-known permission constants. */
    public static final class Permissions {
        // File operations
        public static final String FILE_READ          = "file:read";
        public static final String FILE_WRITE         = "file:write";
        public static final String FILE_DELETE        = "file:delete";
        public static final String FILE_EXECUTE       = "file:execute";

        // Code execution
        public static final String CODE_EXECUTE       = "code:execute";
        public static final String CODE_REVIEW        = "code:review";
        public static final String CODE_DEPLOY        = "code:deploy";

        // Web access
        public static final String WEB_SEARCH         = "web:search";
        public static final String WEB_FETCH          = "web:fetch";
        public static final String WEB_POST           = "web:post";

        // Data operations
        public static final String DATA_READ           = "data:read";
        public static final String DATA_WRITE          = "data:write";
        public static final String DATA_DELETE         = "data:delete";
        public static final String DATA_EXPORT         = "data:export";

        // Agent management
        public static final String AGENT_VIEW          = "agent:view";
        public static final String AGENT_CREATE        = "agent:create";
        public static final String AGENT_MODIFY        = "agent:modify";
        public static final String AGENT_DELETE        = "agent:delete";
        public static final String AGENT_APPROVE       = "agent:approve";

        // Tenant operations
        public static final String TENANT_VIEW         = "tenant:view";
        public static final String TENANT_CREATE       = "tenant:create";
        public static final String TENANT_MODIFY       = "tenant:modify";
        public static final String TENANT_DELETE       = "tenant:delete";

        // Org operations
        public static final String ORG_AUDIT           = "org:audit";
        public static final String ORG_BILLING         = "org:billing";
        public static final String ORG_POLICY          = "org:policy";

        // Messaging
        public static final String MSG_SEND            = "msg:send";
        public static final String MSG_READ            = "msg:read";
        public static final String MSG_BROADCAST       = "msg:broadcast";
    }

    // ---- predefined roles ----

    /** Standard organizational roles for agents. */
    public enum PredefinedRole {
        /** Minimal access — can view docs and search web. */
        VIEWER(Set.of(
            Permissions.FILE_READ, Permissions.WEB_SEARCH, Permissions.DATA_READ,
            Permissions.AGENT_VIEW, Permissions.MSG_READ
        )),

        /** Standard worker — can read/write code and files. */
        CONTRIBUTOR(Set.of(
            Permissions.FILE_READ, Permissions.FILE_WRITE,
            Permissions.WEB_SEARCH, Permissions.WEB_FETCH,
            Permissions.CODE_EXECUTE, Permissions.CODE_REVIEW,
            Permissions.DATA_READ, Permissions.DATA_WRITE,
            Permissions.AGENT_VIEW, Permissions.MSG_READ, Permissions.MSG_SEND
        )),

        /** Senior — can also deploy and manage agents. */
        MAINTAINER(Set.of(
            Permissions.FILE_READ, Permissions.FILE_WRITE, Permissions.FILE_DELETE,
            Permissions.WEB_SEARCH, Permissions.WEB_FETCH, Permissions.WEB_POST,
            Permissions.CODE_EXECUTE, Permissions.CODE_REVIEW, Permissions.CODE_DEPLOY,
            Permissions.DATA_READ, Permissions.DATA_WRITE, Permissions.DATA_EXPORT,
            Permissions.AGENT_VIEW, Permissions.AGENT_CREATE, Permissions.AGENT_MODIFY,
            Permissions.TENANT_VIEW,
            Permissions.MSG_READ, Permissions.MSG_SEND
        )),

        /** Admin — full control including policy and billing. */
        ADMIN(Set.of(
            Permissions.FILE_READ, Permissions.FILE_WRITE, Permissions.FILE_DELETE, Permissions.FILE_EXECUTE,
            Permissions.WEB_SEARCH, Permissions.WEB_FETCH, Permissions.WEB_POST,
            Permissions.CODE_EXECUTE, Permissions.CODE_REVIEW, Permissions.CODE_DEPLOY,
            Permissions.DATA_READ, Permissions.DATA_WRITE, Permissions.DATA_DELETE, Permissions.DATA_EXPORT,
            Permissions.AGENT_VIEW, Permissions.AGENT_CREATE, Permissions.AGENT_MODIFY, Permissions.AGENT_DELETE,
            Permissions.AGENT_APPROVE,
            Permissions.TENANT_VIEW, Permissions.TENANT_CREATE, Permissions.TENANT_MODIFY, Permissions.TENANT_DELETE,
            Permissions.ORG_AUDIT, Permissions.ORG_BILLING, Permissions.ORG_POLICY,
            Permissions.MSG_READ, Permissions.MSG_SEND, Permissions.MSG_BROADCAST
        ));

        private final Set<String> permissions;
        PredefinedRole(Set<String> permissions) { this.permissions = Set.copyOf(permissions); }
        public Set<String> getPermissions() { return permissions; }
    }

    // ---- instance state ----

    /** Custom roles defined by the organization. */
    private final Map<String, Set<String>> customRoles = new LinkedHashMap<>();

    /** Subject (agent/human ID) → set of roles assigned. */
    private final Map<String, Set<String>> subjectRoles = new LinkedHashMap<>();

    /** Scoped permissions: subject → scope → additional permissions. */
    private final Map<String, Map<String, Set<String>>> scopedPermissions = new LinkedHashMap<>();

    // ---- role management ----

    /** Define a custom role with specific permissions. */
    public void defineRole(String roleName, Set<String> permissions) {
        customRoles.put(roleName, Set.copyOf(permissions));
    }

    /** Get all permissions for a role name (predefined or custom). */
    public Set<String> getRolePermissions(String roleName) {
        try {
            return PredefinedRole.valueOf(roleName).getPermissions();
        } catch (IllegalArgumentException e) {
            return customRoles.getOrDefault(roleName, Set.of());
        }
    }

    /** List all defined role names. */
    public Set<String> listRoles() {
        Set<String> roles = new LinkedHashSet<>();
        for (PredefinedRole r : PredefinedRole.values()) roles.add(r.name());
        roles.addAll(customRoles.keySet());
        return roles;
    }

    // ---- assignment ----

    /** Assign a role to a subject. */
    public void assignRole(String subjectId, String roleName) {
        subjectRoles.computeIfAbsent(subjectId, k -> new LinkedHashSet<>()).add(roleName);
    }

    /** Remove a role from a subject. */
    public void revokeRole(String subjectId, String roleName) {
        Set<String> roles = subjectRoles.get(subjectId);
        if (roles != null) roles.remove(roleName);
    }

    /** Grant a specific permission to a subject within a scope. */
    public void grantPermission(String subjectId, String scope, String permission) {
        scopedPermissions
            .computeIfAbsent(subjectId, k -> new LinkedHashMap<>())
            .computeIfAbsent(scope, k -> new LinkedHashSet<>())
            .add(permission);
    }

    /** Revoke a scoped permission. */
    public void revokePermission(String subjectId, String scope, String permission) {
        Map<String, Set<String>> scopes = scopedPermissions.get(subjectId);
        if (scopes != null) {
            Set<String> perms = scopes.get(scope);
            if (perms != null) perms.remove(permission);
        }
    }

    // ---- authorization ----

    /**
     * Check if a subject has a specific permission.
     */
    public boolean checkPermission(String subjectId, String permission) {
        return getEffectivePermissions(subjectId).contains(permission);
    }

    /**
     * Check if a subject has a permission within a scope, with optional attributes.
     */
    public boolean checkPermission(String subjectId, String permission, String scope, Map<String, Object> attributes) {
        Set<String> scopedPerms = getScopedPermissions(subjectId, scope);
        return scopedPerms.contains(permission)
            || getEffectivePermissions(subjectId).contains(permission);
    }

    /**
     * Check a wildcard permission (e.g. "file:*" matches "file:read").
     */
    public boolean checkWildcard(String subjectId, String wildcard) {
        Pattern p = Pattern.compile(wildcard.replace("*", ".*"));
        return getEffectivePermissions(subjectId).stream().anyMatch(perm -> p.matcher(perm).matches());
    }

    /**
     * Get all effective permissions for a subject (union of all role permissions).
     */
    public Set<String> getEffectivePermissions(String subjectId) {
        Set<String> effective = new LinkedHashSet<>();
        Set<String> roles = subjectRoles.getOrDefault(subjectId, Set.of());
        for (String role : roles) {
            effective.addAll(getRolePermissions(role));
        }
        return effective;
    }

    /** Get scoped permissions for a subject. */
    public Set<String> getScopedPermissions(String subjectId, String scope) {
        Map<String, Set<String>> scopes = scopedPermissions.get(subjectId);
        if (scopes == null) return Set.of();
        return scopes.getOrDefault(scope, Set.of());
    }

    /** Get all roles assigned to a subject. */
    public Set<String> getSubjectRoles(String subjectId) {
        return Collections.unmodifiableSet(subjectRoles.getOrDefault(subjectId, Set.of()));
    }

    /** Summary for dashboard. */
    public Map<String, Object> getSummary() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("predefined_roles", PredefinedRole.values().length);
        s.put("custom_roles", customRoles.size());
        s.put("subjects", subjectRoles.size());
        s.put("scoped_permissions", scopedPermissions.size());
        return s;
    }

    /** List all subjects and their roles. */
    public Map<String, Set<String>> getAllAssignments() {
        return Collections.unmodifiableMap(subjectRoles);
    }
}