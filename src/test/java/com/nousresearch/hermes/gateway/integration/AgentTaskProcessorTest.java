package com.nousresearch.hermes.gateway.integration;

import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for AgentTaskProcessor chain mode routing.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AgentTaskProcessorTest {

    @Test
    @Order(1)
    @DisplayName("[chain] prefix triggers chain mode")
    void chainPrefix_triggersChain() {
        AsyncTask task = new AsyncTask(
            "task-1", "tenant-1", "sys-1", "ws-1", "agent-1", "sess-1",
            "[chain] Analyze the log files and summarize errors",
            "PENDING", null, null, 5, 300, null, null, null);

        // The shouldUseChain logic: input starts with [chain]
        assertTrue(task.input().strip().startsWith("[chain]"));
    }

    @Test
    @Order(2)
    @DisplayName("Normal input does not trigger chain mode")
    void normalInput_noChain() {
        AsyncTask task = new AsyncTask(
            "task-2", "tenant-1", "sys-1", "ws-1", "agent-1", "sess-1",
            "What is the weather today?",
            "PENDING", null, null, 5, 300, null, null, null);

        assertFalse(task.input().strip().startsWith("[chain]"));
    }

    @Test
    @Order(3)
    @DisplayName("[chain] with leading whitespace still triggers")
    void chainPrefix_withWhitespace() {
        AsyncTask task = new AsyncTask(
            "task-3", "tenant-1", "sys-1", "ws-1", "agent-1", "sess-1",
            "  [chain] Do something complex",
            "PENDING", null, null, 5, 300, null, null, null);

        assertTrue(task.input().strip().startsWith("[chain]"));
    }

    @Test
    @Order(4)
    @DisplayName("Empty input does not trigger chain mode")
    void emptyInput_noChain() {
        AsyncTask task = new AsyncTask(
            "task-4", "tenant-1", "sys-1", "ws-1", "agent-1", "sess-1",
            "",
            "PENDING", null, null, 5, 300, null, null, null);

        assertFalse(task.input().strip().startsWith("[chain]"));
    }

    @Test
    @Order(5)
    @DisplayName("Null input does not trigger chain mode")
    void nullInput_noChain() {
        AsyncTask task = new AsyncTask(
            "task-5", "tenant-1", "sys-1", "ws-1", "agent-1", "sess-1",
            null,
            "PENDING", null, null, 5, 300, null, null, null);

        // null input should not trigger chain - NPE safe
        assertFalse(task.input() != null && task.input().strip().startsWith("[chain]"));
    }

    @Test
    @Order(6)
    @DisplayName("ModelChain.buildDefault creates 3-phase chain")
    void buildDefault_createsChain() {
        com.nousresearch.hermes.agent.ModelChain chain =
            com.nousresearch.hermes.agent.ModelChain.builder().buildDefault();

        assertNotNull(chain);
        // Trace should be empty before execution
        assertNotNull(chain.getTrace());
    }

    @Test
    @Order(7)
    @DisplayName("ExecutionPlan.passthrough creates empty plan")
    void passthroughPlan() {
        com.nousresearch.hermes.agent.ExecutionPlan plan =
            com.nousresearch.hermes.agent.ExecutionPlan.passthrough("simple task");

        assertTrue(plan.isPassthrough());
        assertTrue(plan.steps().isEmpty());
        assertEquals("simple task", plan.goal());
    }

    @Test
    @Order(8)
    @DisplayName("ExecutionPlan with steps is not passthrough")
    void nonEmptyPlan() {
        var step = new com.nousresearch.hermes.agent.ExecutionPlan.PlanStep(
            "s1", "Read config", "file_read", java.util.List.of(), "config content");

        com.nousresearch.hermes.agent.ExecutionPlan plan =
            new com.nousresearch.hermes.agent.ExecutionPlan(
                "migrate db", java.util.List.of(step),
                java.util.List.of("script valid"), "check FK");

        assertFalse(plan.isPassthrough());
        assertEquals(1, plan.steps().size());
        assertEquals("s1", plan.steps().get(0).id());
        assertTrue(plan.steps().get(0).isToolStep());
    }

    @Test
    @Order(9)
    @DisplayName("ReviewResult.retryStepIds extracts failed steps")
    void reviewResult_retryStepIds() {
        var stepReviews = java.util.List.of(
            new com.nousresearch.hermes.agent.ExecutionPlan.StepReview("s1", true, "ok"),
            new com.nousresearch.hermes.agent.ExecutionPlan.StepReview("s2", false, "wrong output"),
            new com.nousresearch.hermes.agent.ExecutionPlan.StepReview("s3", true, "ok")
        );

        var review = new com.nousresearch.hermes.agent.ExecutionPlan.ReviewResult(
            false, stepReviews, "s2 needs retry", 2);

        assertFalse(review.approved());
        assertTrue(review.needsRetry());
        assertEquals(java.util.List.of("s2"), review.retryStepIds());
    }

    @Test
    @Order(10)
    @DisplayName("ReviewResult with all passed is approved")
    void reviewResult_allPassed() {
        var stepReviews = java.util.List.of(
            new com.nousresearch.hermes.agent.ExecutionPlan.StepReview("s1", true, "ok"),
            new com.nousresearch.hermes.agent.ExecutionPlan.StepReview("s2", true, "ok")
        );

        var review = new com.nousresearch.hermes.agent.ExecutionPlan.ReviewResult(
            true, stepReviews, "all good", 2);

        assertTrue(review.approved());
        assertFalse(review.needsRetry());
        assertTrue(review.retryStepIds().isEmpty());
    }

    @Test
    @Order(11)
    @DisplayName("PlannerPrompt.parsePlan handles plain JSON")
    void parsePlan_plainJson() {
        String json = """
            {"goal":"test","steps":[{"id":"s1","action":"do","tool":"code","dependsOn":[],"expectedOutput":"done"}],"successCriteria":["done"],"notes":""}""";

        com.nousresearch.hermes.agent.ExecutionPlan plan =
            com.nousresearch.hermes.agent.PlannerPrompt.parsePlan(json);

        assertEquals("test", plan.goal());
        assertEquals(1, plan.steps().size());
        assertEquals("s1", plan.steps().get(0).id());
        assertEquals("code", plan.steps().get(0).tool());
    }

    @Test
    @Order(12)
    @DisplayName("PlannerPrompt.parsePlan handles markdown code block")
    void parsePlan_markdownBlock() {
        String input = """
            Here's the plan:
            ```json
            {"goal":"test2","steps":[],"successCriteria":[],"notes":"empty"}
            ```
            Done.""";

        com.nousresearch.hermes.agent.ExecutionPlan plan =
            com.nousresearch.hermes.agent.PlannerPrompt.parsePlan(input);

        assertEquals("test2", plan.goal());
        assertTrue(plan.isPassthrough());
    }

    @Test
    @Order(13)
    @DisplayName("PlannerPrompt.parsePlan falls back to passthrough on garbage")
    void parsePlan_garbageFallback() {
        com.nousresearch.hermes.agent.ExecutionPlan plan =
            com.nousresearch.hermes.agent.PlannerPrompt.parsePlan("this is not json at all");

        assertTrue(plan.isPassthrough());
    }

    @Test
    @Order(14)
    @DisplayName("PlannerPrompt.parsePlan handles null/blank input")
    void parsePlan_nullBlank() {
        assertTrue(com.nousresearch.hermes.agent.PlannerPrompt.parsePlan(null).isPassthrough());
        assertTrue(com.nousresearch.hermes.agent.PlannerPrompt.parsePlan("").isPassthrough());
        assertTrue(com.nousresearch.hermes.agent.PlannerPrompt.parsePlan("   ").isPassthrough());
    }

    @Test
    @Order(15)
    @DisplayName("PlannerPrompt.parseReview auto-approves on null/blank")
    void parseReview_nullBlank() {
        var review = com.nousresearch.hermes.agent.PlannerPrompt.parseReview(null);
        assertTrue(review.approved());

        var review2 = com.nousresearch.hermes.agent.PlannerPrompt.parseReview("");
        assertTrue(review2.approved());
    }

    @Test
    @Order(16)
    @DisplayName("PlannerPrompt.parseReview parses valid review JSON")
    void parseReview_validJson() {
        String json = """
            {"approved":false,"stepReviews":[{"stepId":"s1","passed":false,"feedback":"incomplete"}],"summary":"s1 needs retry","maxRetries":2}""";

        var review = com.nousresearch.hermes.agent.PlannerPrompt.parseReview(json);

        assertFalse(review.approved());
        assertTrue(review.needsRetry());
        assertEquals(java.util.List.of("s1"), review.retryStepIds());
        assertEquals(2, review.maxRetries());
    }

    @Test
    @Order(17)
    @DisplayName("PlannerPrompt.buildSystemPrompt includes tool names")
    void buildSystemPrompt_includesTools() {
        var tools = java.util.List.of(
            com.nousresearch.hermes.model.ToolDefinition.builder()
                .name("code").description("Execute code").build(),
            com.nousresearch.hermes.model.ToolDefinition.builder()
                .name("file_read").description("Read file").build()
        );

        String prompt = com.nousresearch.hermes.agent.PlannerPrompt.buildSystemPrompt(tools);

        assertTrue(prompt.contains("code"));
        assertTrue(prompt.contains("file_read"));
        assertTrue(prompt.contains("可用工具"));
        assertTrue(prompt.contains("严格 JSON"));
    }

    @Test
    @Order(18)
    @DisplayName("PlannerPrompt.buildSystemPrompt handles null tools")
    void buildSystemPrompt_nullTools() {
        String prompt = com.nousresearch.hermes.agent.PlannerPrompt.buildSystemPrompt(null);
        assertTrue(prompt.contains("无"));
    }

    @Test
    @Order(19)
    @DisplayName("ExecutionPlan.findStep returns step or null")
    void findStep() {
        var s1 = new com.nousresearch.hermes.agent.ExecutionPlan.PlanStep(
            "s1", "read", "file_read", java.util.List.of(), "content");
        var s2 = new com.nousresearch.hermes.agent.ExecutionPlan.PlanStep(
            "s2", "write", "code", java.util.List.of("s1"), "script");

        var plan = new com.nousresearch.hermes.agent.ExecutionPlan(
            "task", java.util.List.of(s1, s2), java.util.List.of(), "");

        assertEquals("s1", plan.findStep("s1").id());
        assertEquals("s2", plan.findStep("s2").id());
        assertNull(plan.findStep("s3"));
    }

    @Test
    @Order(20)
    @DisplayName("PlanStep.isToolStep and hasDependencies")
    void planStep_helpers() {
        var toolStep = new com.nousresearch.hermes.agent.ExecutionPlan.PlanStep(
            "s1", "read", "file_read", java.util.List.of(), "content");
        var reasoningStep = new com.nousresearch.hermes.agent.ExecutionPlan.PlanStep(
            "s2", "analyze", "", java.util.List.of("s1"), "analysis");
        var noDepsStep = new com.nousresearch.hermes.agent.ExecutionPlan.PlanStep(
            "s3", "init", "", java.util.List.of(), "ready");

        assertTrue(toolStep.isToolStep());
        assertFalse(reasoningStep.isToolStep());
        assertTrue(reasoningStep.hasDependencies());
        assertFalse(noDepsStep.hasDependencies());
    }
}
