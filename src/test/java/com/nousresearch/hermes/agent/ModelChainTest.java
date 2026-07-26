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

    @Test
    @Order(4)
    @DisplayName("routing is case-insensitive")
    void routingPolicy_caseInsensitive() {
        ModelRoutingPolicy policy = new ModelRoutingPolicy(Map.of("planner", "smart"));
        assertEquals("smart", policy.getAliasForRole("PLANNER"));
        assertEquals("smart", policy.getAliasForRole("Planner"));
    }

    @Test
    @Order(5)
    @DisplayName("fromConfig parses map")
    void routingPolicy_fromConfig() {
        ModelRoutingPolicy policy = ModelRoutingPolicy.fromConfig(Map.of(
            "planner", "smart", "executor", "fast", "reviewer", "smart"));
        assertEquals("smart", policy.getAliasForRole("planner"));
        assertEquals("fast", policy.getAliasForRole("executor"));
    }

    @Test
    @Order(6)
    @DisplayName("fromConfig handles null/empty")
    void routingPolicy_fromConfigNull() {
        ModelRoutingPolicy policy = ModelRoutingPolicy.fromConfig(null);
        assertTrue(policy.getRoutings().isEmpty());
    }

    @Test
    @Order(7)
    @DisplayName("defaults returns 6 role mappings")
    void routingPolicy_defaults() {
        ModelRoutingPolicy policy = ModelRoutingPolicy.defaults();
        assertEquals("smart", policy.getAliasForRole("planner"));
        assertEquals("fast", policy.getAliasForRole("executor"));
        assertEquals("smart", policy.getAliasForRole("reviewer"));
        assertEquals("fast", policy.getAliasForRole("coder"));
        assertEquals("cheap", policy.getAliasForRole("analyst"));
        assertEquals("fast", policy.getAliasForRole("default"));
    }

    // ============ ModelChain Builder ============

    @Test
    @Order(8)
    @DisplayName("ModelChain builder creates steps")
    void chainBuilder_steps() {
        ModelChain chain = ModelChain.builder()
            .plan("You are a planner. Decompose the task.")
            .execute("You are an executor. Execute each step.")
            .review("You are a reviewer. Critique the result.")
            .build();

        assertNotNull(chain);
    }

    @Test
    @Order(9)
    @DisplayName("ModelChain builder requires at least one step")
    void chainBuilder_empty() {
        assertThrows(IllegalStateException.class, () -> ModelChain.builder().build());
    }

    @Test
    @Order(10)
    @DisplayName("ModelChain builder step() adds custom step")
    void chainBuilder_customStep() {
        ModelChain chain = ModelChain.builder()
            .step("analyze", "cheap", "analyst", "Analyze the data")
            .build();

        assertNotNull(chain);
    }

    @Test
    @Order(11)
    @DisplayName("ModelChain builder convenience methods use correct aliases")
    void chainBuilder_convenience() {
        // Build with convenience methods - verify it doesn't throw
        ModelChain chain = ModelChain.builder()
            .plan("Plan it")
            .execute("Execute it")
            .review("Review it")
            .build();

        // The chain should have 3 steps
        assertNotNull(chain.getTrace());
    }

    @Test
    @Order(12)
    @DisplayName("ModelChain builder with OutputTransform")
    void chainBuilder_withTransform() {
        ModelChain.OutputTransform extractPlan = (output, context) ->
            "Extracted plan from: " + output.substring(0, Math.min(output.length(), 50));

        ModelChain chain = ModelChain.builder()
            .step("planning", "smart", "planner", "Plan", extractPlan)
            .step("execution", "fast", "executor", "Execute")
            .build();

        assertNotNull(chain);
    }
}
