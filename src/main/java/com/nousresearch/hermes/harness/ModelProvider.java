package com.nousresearch.hermes.harness;

import com.nousresearch.hermes.model.ChatCompletionResponse;
import com.nousresearch.hermes.model.ModelMessage;
import com.nousresearch.hermes.model.ToolDefinition;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Unified model access layer.
 *
 * <p>Replaces the parallel {@code ModelClient} + {@code BaseTransport}
 * abstractions. {@code ModelClient} already implements this interface;
 * future Transport-based providers implement it too.</p>
 */
public interface ModelProvider {

    /** Synchronous chat completion. */
    ChatCompletionResponse chat(
        List<ModelMessage> messages,
        List<ToolDefinition> tools,
        boolean stream,
        Map<String, Object> extraParams
    );

    /** Streaming chat completion with delta callback. */
    ChatCompletionResponse chatStream(
        List<ModelMessage> messages,
        List<ToolDefinition> tools,
        Map<String, Object> params,
        Consumer<String> onDelta
    );

    /** Create embedding for a text. */
    float[] embed(String text);

    /** Whether this provider handles the given model id. */
    boolean supportsModel(String model);
}
