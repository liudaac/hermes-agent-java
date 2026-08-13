package com.nousresearch.hermes.harness.compaction;

import com.nousresearch.hermes.model.ModelMessage;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

class BasicCompactionEngineTest {

    private BasicCompactionEngine engine;

    @BeforeEach
    void setUp() {
        engine = new BasicCompactionEngine(0.5, 0.3, 4, 1000, 200);
    }

    @Test
    @DisplayName("No compaction when below threshold")
    void noCompactionBelowThreshold() {
        List<ModelMessage> history = new ArrayList<>(List.of(
            ModelMessage.system("system"),
            ModelMessage.user("short"),
            ModelMessage.assistant("reply")
        ));

        CompactionResult result = engine.compactIfNeeded(
            history, CompactionTrigger.PRESSURE, null);

        assertFalse(result.success());
        assertEquals(3, history.size());
    }

    @Test
    @DisplayName("Pressure triggers compaction above threshold")
    void pressureTriggersCompaction() {
        // Create enough messages to exceed threshold (800 available tokens, 50% = 400)
        List<ModelMessage> history = new ArrayList<>();
        history.add(ModelMessage.system("sys"));
        for (int i = 0; i < 20; i++) {
            history.add(ModelMessage.user("message number " + i + " with some padding text to make it longer"));
            history.add(ModelMessage.assistant("response " + i + " with some padding text to make it longer too"));
        }

        int originalSize = history.size();
        CompactionResult result = engine.compactIfNeeded(
            history, CompactionTrigger.PRESSURE, null);

        assertTrue(result.success());
        assertTrue(result.messagesCompacted() > 0);
        assertTrue(history.size() < originalSize);
        // Should contain a summary message
        boolean hasSummary = history.stream()
            .anyMatch(m -> m.getContent() != null && m.getContent().contains("[context-compressed]"));
        assertTrue(hasSummary);
    }

    @Test
    @DisplayName("CONTEXT_OVERFLOW always attempts compaction")
    void overflowAlwaysCompacts() {
        List<ModelMessage> history = new ArrayList<>();
        history.add(ModelMessage.system("sys"));
        for (int i = 0; i < 10; i++) {
            history.add(ModelMessage.user("msg " + i));
            history.add(ModelMessage.assistant("reply " + i));
        }

        CompactionResult result = engine.compactIfNeeded(
            history, CompactionTrigger.CONTEXT_OVERFLOW, null);

        assertTrue(result.success());
        assertTrue(history.size() < 21);
    }

    @Test
    @DisplayName("Compaction preserves recent messages")
    void preservesRecentMessages() {
        List<ModelMessage> history = new ArrayList<>();
        history.add(ModelMessage.system("sys"));
        for (int i = 0; i < 15; i++) {
            history.add(ModelMessage.user("old msg " + i));
        }
        history.add(ModelMessage.user("RECENT_1"));
        history.add(ModelMessage.user("RECENT_2"));
        history.add(ModelMessage.user("RECENT_3"));
        history.add(ModelMessage.user("RECENT_4"));

        engine.compactIfNeeded(history, CompactionTrigger.CONTEXT_OVERFLOW, null);

        // Recent messages should be preserved
        assertTrue(history.stream().anyMatch(m -> "RECENT_1".equals(m.getContent())));
        assertTrue(history.stream().anyMatch(m -> "RECENT_4".equals(m.getContent())));
    }

    @Test
    @DisplayName("Compaction preserves system prompt")
    void preservesSystemPrompt() {
        List<ModelMessage> history = new ArrayList<>();
        history.add(ModelMessage.system("IMPORTANT_SYSTEM_PROMPT"));
        for (int i = 0; i < 15; i++) {
            history.add(ModelMessage.user("msg " + i));
            history.add(ModelMessage.assistant("reply " + i));
        }

        engine.compactIfNeeded(history, CompactionTrigger.CONTEXT_OVERFLOW, null);

        assertEquals("IMPORTANT_SYSTEM_PROMPT", history.get(0).getContent());
    }

    @Test
    @DisplayName("Extractive fallback works without model client")
    void extractiveFallback() {
        List<ModelMessage> history = new ArrayList<>();
        history.add(ModelMessage.system("sys"));
        for (int i = 0; i < 20; i++) {
            history.add(ModelMessage.user("user message " + i));
            history.add(ModelMessage.assistant("assistant response " + i));
        }

        CompactionResult result = engine.compact(history, null);

        assertTrue(result.success());
        assertNotNull(result.summary());
        assertFalse(result.summary().isBlank());
    }

    @Test
    @DisplayName("Skips when too few messages")
    void skipsWhenTooFewMessages() {
        List<ModelMessage> history = new ArrayList<>(List.of(
            ModelMessage.system("sys"),
            ModelMessage.user("hi"),
            ModelMessage.assistant("hello")
        ));

        CompactionResult result = engine.compact(history, null);
        assertFalse(result.success());
    }

    @Test
    @DisplayName("Tool pairing: doesn't orphan tool results")
    void toolPairingPreserved() {
        List<ModelMessage> history = new ArrayList<>();
        history.add(ModelMessage.system("sys"));
        // Old messages
        for (int i = 0; i < 10; i++) {
            history.add(ModelMessage.user("msg " + i));
        }
        // Tool call + result pair near the boundary
        ModelMessage assistantWithTools = ModelMessage.assistant("let me check");
        com.nousresearch.hermes.model.ToolCall.Function func = new com.nousresearch.hermes.model.ToolCall.Function();
        func.setName("read_file");
        func.setArguments("{\"path\":\"a\"}");
        com.nousresearch.hermes.model.ToolCall tc = new com.nousresearch.hermes.model.ToolCall();
        tc.setId("call-1");
        tc.setType("function");
        tc.setFunction(func);
        assistantWithTools.setToolCalls(List.of(tc));
        history.add(assistantWithTools);
        history.add(ModelMessage.tool("file contents", "call-1"));
        // Recent messages
        history.add(ModelMessage.user("RECENT"));

        engine.compactIfNeeded(history, CompactionTrigger.CONTEXT_OVERFLOW, null);

        // If assistant with tool_calls is kept, tool result must also be kept
        for (int i = 0; i < history.size(); i++) {
            ModelMessage m = history.get(i);
            if (m.getToolCalls() != null && !m.getToolCalls().isEmpty()) {
                // Next message should be the tool result
                if (i + 1 < history.size()) {
                    assertEquals("tool", history.get(i + 1).getRole());
                }
            }
        }
    }
}
