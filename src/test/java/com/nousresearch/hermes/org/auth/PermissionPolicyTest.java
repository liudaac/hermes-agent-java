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

}
