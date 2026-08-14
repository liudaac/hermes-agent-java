package com.nousresearch.hermes.memory.store;

import com.nousresearch.hermes.model.ChatCompletionResponse;
import com.nousresearch.hermes.model.ModelClient;
import com.nousresearch.hermes.model.ModelMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link LlmMemoryFunctions}.
 *
 * <p>Verifies that LLM-backed summarisation and fact extraction work correctly,
 * including graceful fallback when the LLM returns empty or fails.</p>
 */
class LlmMemoryFunctionsTest {

    private ChatCompletionResponse responseWithContent(String content) {
        return new ChatCompletionResponse(
            ModelMessage.assistant(content), "stop", false, null, null
        );
    }

    @Test
    @DisplayName("summaryFunction calls LLM and returns its response")
    void summariseWithLlm_success() {
        ModelClient mockClient = mock(ModelClient.class);
        when(mockClient.chatCompletion(anyList(), anyList(), anyBoolean(), anyMap()))
            .thenReturn(responseWithContent("• User discussed API rate limits\n• Decided to use Redis for caching"));

        var funcs = new LlmMemoryFunctions(mockClient);
        var messages = List.of(
            new MemoryStore.SessionMessage("user", "What's the API rate limit?", Instant.now()),
            new MemoryStore.SessionMessage("assistant", "It's 100 requests per minute.", Instant.now())
        );

        String summary = funcs.summaryFunction().summarise(messages);

        assertNotNull(summary);
        assertTrue(summary.contains("rate limits"));
        verify(mockClient, times(1)).chatCompletion(anyList(), anyList(), anyBoolean(), anyMap());
    }

    @Test
    @DisplayName("summaryFunction falls back to concat when LLM returns null content")
    void summariseWithLlm_emptyResponse_fallsBack() {
        ModelClient mockClient = mock(ModelClient.class);
        when(mockClient.chatCompletion(anyList(), anyList(), anyBoolean(), anyMap()))
            .thenReturn(new ChatCompletionResponse(null, "stop", false));

        var funcs = new LlmMemoryFunctions(mockClient);
        var messages = List.of(
            new MemoryStore.SessionMessage("user", "Hello", Instant.now()),
            new MemoryStore.SessionMessage("assistant", "Hi there", Instant.now())
        );

        String summary = funcs.summaryFunction().summarise(messages);

        assertNotNull(summary);
        assertTrue(summary.contains("[user]"));
        assertTrue(summary.contains("[assistant]"));
    }

    @Test
    @DisplayName("summaryFunction falls back to concat when LLM throws")
    void summariseWithLlm_exception_fallsBack() {
        ModelClient mockClient = mock(ModelClient.class);
        when(mockClient.chatCompletion(anyList(), anyList(), anyBoolean(), anyMap()))
            .thenThrow(new RuntimeException("Connection refused"));

        var funcs = new LlmMemoryFunctions(mockClient);
        var messages = List.of(
            new MemoryStore.SessionMessage("user", "Test message", Instant.now())
        );

        String summary = funcs.summaryFunction().summarise(messages);

        assertNotNull(summary);
        assertTrue(summary.contains("[user]"));
        assertTrue(summary.contains("Test message"));
    }

}
