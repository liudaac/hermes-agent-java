package com.nousresearch.hermes.agent;

import org.junit.jupiter.api.*;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * F1: ModelChain + ModelRoutingPolicy tests.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ModelChainTest {

    // ============ ModelRoutingPolicy ============

    @Test
    @Order(1)
    @DisplayName("ModelRoutingPolicy maps roles to aliases")
    void routingPolicy_basic() {
        ModelRoutingPolicy policy = new ModelRoutingPolicy(Map.of(
            "planner", "smart", "executor", "fast"));
        assertEquals("smart", policy.getAliasForRole("planner"));
        assertEquals("fast", policy.getAliasForRole("executor"));
    }

    @Test
    @Order(2)
    @DisplayName("getAliasForRole returns null for unmapped role")
    void routingPolicy_unmapped() {
        ModelRoutingPolicy policy = new ModelRoutingPolicy(Map.of("planner", "smart"));
        assertNull(policy.getAliasForRole("unknown"));
        assertNull(policy.getAliasForRole(null));
    }

    @Test
    @Order(3)
    @DisplayName("hasRouting checks correctly")
    void routingPolicy_hasRouting() {
        ModelRoutingPolicy policy = new ModelRoutingPolicy(Map.of("planner", "smart"));
        assertTrue(policy.hasRouting("planner"));
        assertFalse(policy.hasRouting("unknown"));
    }


    // ============ ModelChain Builder ============

}
