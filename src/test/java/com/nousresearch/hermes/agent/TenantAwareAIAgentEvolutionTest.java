package com.nousresearch.hermes.agent;

import com.nousresearch.hermes.org.evolution.FailureCase;
import com.nousresearch.hermes.org.evolution.SelfEvolutionEngine;
import com.nousresearch.hermes.tenant.core.TenantContext;
import com.nousresearch.hermes.tenant.core.TenantProvisioningRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for AI原生组织 第二刀：SelfEvolution 回路闭合
 * 
 * Verifies: failure recording, root cause analysis, success tracking,
 * evolution prompt generation, skill suggestions, and cross-agent sharing.
 */
class TenantAwareAIAgentEvolutionTest {

    private SelfEvolutionEngine engine;

    @BeforeEach
    void setUp() {
        engine = new SelfEvolutionEngine();
    }

    // ======== Failure Recording ========

    @Test
    void recordFailureIncrementsTotal() {
        assertEquals(0, engine.getTotalFailures());
        recordFailure("agent-1", "Task failed", FailureCase.RootCause.WRONG_TOOL);
        assertEquals(1, engine.getTotalFailures());
    }

    @Test
    void recordResolvedFailureTracksCorrectly() {
        var fc = new FailureCase.Builder("agent-1", "Task", "Failed")
            .rootCause(FailureCase.RootCause.WRONG_TOOL)
            .severity(FailureCase.Severity.MEDIUM)
            .lesson("Use find_teammate before delegating")
            .resolved(true)
            .build();
        engine.recordFailure(fc);
        assertEquals(1, engine.getResolvedFailures());
    }

    // ======== Root Cause Detection ========

    @Test
    void detectPermissionDeniedRootCause() {
        var rc = invokeDetermineRootCause(
            new RuntimeException("Permission denied: access to /etc/shadow"), "file_read");
        assertEquals(FailureCase.RootCause.PERMISSION_DENIED, rc);
    }

    @Test
    void detectWrongToolRootCause() {
        var rc = invokeDetermineRootCause(
            new RuntimeException("Unknown tool: nonexistent_tool"), "nonexistent_tool");
        assertEquals(FailureCase.RootCause.WRONG_TOOL, rc);
    }

    @Test
    void detectTimeoutRootCause() {
        var rc = invokeDetermineRootCause(
            new RuntimeException("Connection timed out after 30 seconds"), "web_search");
        assertEquals(FailureCase.RootCause.INSUFFICIENT_CONTEXT, rc);
    }

    @Test
    void detectAmbiguousRootCause() {
        var rc = invokeDetermineRootCause(
            new RuntimeException("Ambiguous input: multiple files match"), "file_read");
        assertEquals(FailureCase.RootCause.AMBIGUOUS_PROMPT, rc);
    }

    @Test
    void defaultRootCauseIsWrongTool() {
        var rc = invokeDetermineRootCause(
            new RuntimeException((String) null), "some_tool");
        assertEquals(FailureCase.RootCause.WRONG_TOOL, rc);
    }

    // ======== Evolution Prompt ========

    @Test
    void evolutionPromptEmptyWhenNoFailures() {
        String ctx = engine.buildEvolutionPrompt("agent-fresh");
        assertTrue(ctx.contains("# Self-Evolution Context"));
        // No lessons since no failures
    }

    @Test
    void evolutionPromptContainsLessonsAfterResolvedFailures() {
        for (int i = 0; i < 3; i++) {
            var fc = new FailureCase.Builder("agent-evo", "Task " + i, "Failed")
                .rootCause(FailureCase.RootCause.WRONG_TOOL)
                .severity(FailureCase.Severity.MEDIUM)
                .lesson("Use find_teammate before delegating")
                .resolved(true)
                .build();
            engine.recordFailure(fc);
        }

        String ctx = engine.buildEvolutionPrompt("agent-evo");
        assertTrue(ctx.contains("Self-Evolution"));
        assertTrue(ctx.contains("find_teammate"));
    }

    // ======== Pattern Detection ========

    @Test
    void detectPatternsAfterMultipleFailures() {
        for (int i = 0; i < 3; i++) {
            recordFailure("agent-1", "Task " + i, FailureCase.RootCause.WRONG_TOOL);
        }
        recordFailure("agent-1", "Other", FailureCase.RootCause.PERMISSION_DENIED);

        var patterns = engine.detectPatterns("agent-1", 2);
        assertTrue(patterns.stream().anyMatch(p -> p.rootCause() == FailureCase.RootCause.WRONG_TOOL));
        assertEquals(3, patterns.get(0).occurrences());
    }

    @Test
    void orgWidePatternDetection() {
        recordFailure("agent-1", "T1", FailureCase.RootCause.WRONG_TOOL);
        recordFailure("agent-2", "T2", FailureCase.RootCause.WRONG_TOOL);
        recordFailure("agent-3", "T3", FailureCase.RootCause.PERMISSION_DENIED);

        var patterns = engine.detectOrgPatterns(2);
        assertTrue(patterns.stream().anyMatch(p -> p.rootCause() == FailureCase.RootCause.WRONG_TOOL));
    }

    // ======== Success & Skill Suggestions ========

    @Test
    void successPatternsTracked() {
        engine.recordSuccess("agent-1", "find_teammate", "Found teammate");
        engine.recordSuccess("agent-1", "delegate_task", "Delegated");

        var patterns = engine.getSuccessPatterns("agent-1");
        assertEquals(2, patterns.size());
        assertTrue(patterns.contains("find_teammate"));
        assertTrue(patterns.contains("delegate_task"));
    }

    @Test
    void skillSuggestionsGeneratedAfterFailures() {
        for (int i = 0; i < 3; i++) {
            recordFailure("agent-1", "Task", FailureCase.RootCause.HALLUCINATION);
        }
        var s = engine.suggestSkill("agent-1");
        assertNotNull(s);
        assertEquals("fact-verification-checklist", s.skillName());
    }

    // ======== Cross-Agent Learning ========

    @Test
    void crossAgentLessonSharing() {
        var fc = new FailureCase.Builder("agent-src", "Task", "Failed")
            .rootCause(FailureCase.RootCause.INSUFFICIENT_CONTEXT)
            .severity(FailureCase.Severity.MEDIUM)
            .lesson("Always check context before tool execution")
            .build();
        engine.recordFailure(fc);

        engine.shareLesson("agent-src", "agent-dst", fc.getId());

        var history = engine.getHistory("agent-dst", 10);
        boolean hasSharedLesson = history.stream()
            .anyMatch(e -> e.type() == SelfEvolutionEngine.EvolutionEvent.Type.LESSON_SHARED);
        assertTrue(hasSharedLesson);
    }

    // ======== Summary ========

    @Test
    void summaryHasAllFields() {
        recordFailure("agent-1", "Task", FailureCase.RootCause.WRONG_TOOL);
        var summary = engine.getSummary();
        assertTrue(summary.containsKey("total_failures"));
        assertTrue(summary.containsKey("resolved"));
        assertTrue(summary.containsKey("resolution_rate"));
        assertTrue(summary.containsKey("root_causes"));
        assertEquals(1, (long) summary.get("total_failures"));
    }

    // ======== Helpers ========

    private void recordFailure(String agentId, String task, FailureCase.RootCause cause) {
        var fc = new FailureCase.Builder(agentId, task, task)
            .rootCause(cause)
            .severity(FailureCase.Severity.MEDIUM)
            .lesson(task)
            .build();
        engine.recordFailure(fc);
    }

    // Reflection helper to invoke private static determineRootCause method
    private static FailureCase.RootCause invokeDetermineRootCause(
            Exception e, String toolName) {
        try {
            var method = TenantAwareAIAgent.class.getDeclaredMethod(
                "determineRootCause", Exception.class, String.class);
            method.setAccessible(true);
            return (FailureCase.RootCause) method.invoke(null, e, toolName);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
}
