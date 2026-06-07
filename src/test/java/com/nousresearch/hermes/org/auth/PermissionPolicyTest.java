package com.nousresearch.hermes.org.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PermissionPolicyTest {

    private PermissionPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new PermissionPolicy();

        // Set up RBAC: assign roles
        policy.rbac().assignRole("agent-code", "CONTRIBUTOR");
        policy.rbac().assignRole("agent-admin", "ADMIN");
        policy.rbac().assignRole("agent-viewer", "VIEWER");

        // Set up ABAC: add policies
        policy.abac().addPolicy(AttributeBasedAccessControl.classificationCheck(10));
        policy.abac().addPolicy(AttributeBasedAccessControl.deployGate(20));
        policy.abac().addPolicy(AttributeBasedAccessControl.departmentBoundary(30));
    }

    @Test
    void testContributorCanExecuteCode() {
        assertTrue(policy.check("agent-code", "code:execute"));
    }

    @Test
    void testViewerCannotExecuteCode() {
        assertFalse(policy.check("agent-viewer", "code:execute"));
    }

    @Test
    void testAdminCanDoEverything() {
        assertTrue(policy.check("agent-admin", "code:deploy"));
        assertTrue(policy.check("agent-admin", "tenant:delete"));
        assertTrue(policy.check("agent-admin", "org:policy"));
    }

    @Test
    void testClassificationCheckDeniesLowClearance() {
        var result = policy.authorize(
            "agent-code", "data:read", "/data/finance-report",
            Map.of("clearance_level", 0, "department", "engineering"),
            Map.of("classification", "RESTRICTED"),
            Map.of()
        );
        assertFalse(result.allowed());
        assertEquals("ABAC", result.source());
    }

    @Test
    void testClassificationCheckAllowsHighClearance() {
        var result = policy.authorize(
            "agent-code", "data:read", "/data/finance-report",
            Map.of("clearance_level", 5, "department", "engineering"),
            Map.of("classification", "CONFIDENTIAL"),
            Map.of()
        );
        assertTrue(result.allowed());
    }

    @Test
    void testDeployGateBlocksUntaggedAgent() {
        policy.rbac().assignRole("agent-deploy", "MAINTAINER");
        var result = policy.authorize(
            "agent-deploy", "code:deploy", "/deploy",
            Map.of("tags", Set.of()),
            Map.of(),
            Map.of()
        );
        assertFalse(result.allowed());
    }

    @Test
    void testDeployGateAllowsTaggedAgent() {
        policy.rbac().assignRole("agent-deploy2", "MAINTAINER");
        var result = policy.authorize(
            "agent-deploy2", "code:deploy", "/deploy",
            Map.of("tags", Set.of("production-safe")),
            Map.of(),
            Map.of()
        );
        assertTrue(result.allowed());
    }

    @Test
    void testOverrideGrantsAccess() {
        // Viewer normally can't delete
        assertFalse(policy.check("agent-viewer", "file:delete"));

        // Grant override
        policy.grantOverride("agent-viewer", "file:delete");
        assertTrue(policy.check("agent-viewer", "file:delete"));

        // Revoke
        policy.revokeOverride("agent-viewer", "file:delete");
        assertFalse(policy.check("agent-viewer", "file:delete"));
    }

    @Test
    void testWildcardOverride() {
        policy.grantOverride("agent-viewer", "*");
        assertTrue(policy.check("agent-viewer", "code:deploy"));
        assertTrue(policy.check("agent-viewer", "tenant:delete"));
    }

    @Test
    void testBatchAuthorization() {
        var results = policy.authorizeBatch(
            "agent-code",
            java.util.List.of("file:read", "file:write", "code:deploy", "tenant:delete"),
            "/workspace",
            Map.of("department", "engineering"),
            Map.of(),
            Map.of()
        );

        assertTrue(results.get("file:read").allowed());
        assertTrue(results.get("file:write").allowed());
        assertFalse(results.get("code:deploy").allowed());  // blocked by deploy gate
        assertFalse(results.get("tenant:delete").allowed()); // not in CONTRIBUTOR role
    }

    @Test
    void testRbacPredefinedRoles() {
        Set<String> viewerPerms = RoleBasedAccessControl.PredefinedRole.VIEWER.getPermissions();
        Set<String> adminPerms = RoleBasedAccessControl.PredefinedRole.ADMIN.getPermissions();

        assertTrue(viewerPerms.contains("file:read"));
        assertFalse(viewerPerms.contains("file:write"));
        assertTrue(adminPerms.contains("file:delete"));
        assertTrue(adminPerms.contains("org:policy"));
    }

    @Test
    void testCustomRole() {
        policy.rbac().defineRole("DEVOPS", Set.of("code:deploy", "code:review", "data:read"));
        policy.rbac().assignRole("agent-dev", "DEVOPS");

        assertTrue(policy.check("agent-dev", "code:deploy"));
        assertFalse(policy.check("agent-dev", "file:write"));
    }

    @Test
    void testDepartmentBoundary() {
        var result = policy.authorize(
            "agent-code", "file:read", "/docs/legal",
            Map.of("department", "engineering"),
            Map.of("department", "legal"),
            Map.of()
        );
        assertFalse(result.allowed());
    }
}
