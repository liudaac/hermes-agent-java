package com.nousresearch.hermes.agent.transports;

import com.alibaba.fastjson2.JSONObject;

import java.util.List;

/**
 * Represents a response from a transport.
 * Mirrors Python TransportResponse dataclass.
 */
public record TransportResponse(
    String content,
    List<TransportMessage.ToolCall> toolCalls,
    String model,
    String finishReason,
    Integer promptTokens,
    Integer completionTokens,
    Integer totalTokens,
    JSONObject rawResponse,
    boolean isError,
    String errorMessage
) {
    /**
     * Create a successful response.
     */
    public static TransportResponse success(
        String content,
        List<TransportMessage.ToolCall> toolCalls,
        String model,
        String finishReason,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        JSONObject rawResponse
    ) {
        return new TransportResponse(
            content, toolCalls, model, finishReason,
            promptTokens, completionTokens, totalTokens,
            rawResponse, false, null
        );
    }

    /**
     * Create an error response.
     */
    public static TransportResponse error(String errorMessage) {
        return new TransportResponse(
            null, null, null, null,
            null, null, null,
            null, true, errorMessage
        );
    }

    /**
     * Check if the response contains tool calls.
     */
    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    /**
     * Check if the response is complete.
     */
    public boolean isComplete() {
        return "stop".equals(finishReason) || "end_turn".equals(finishReason);
    }

    /**
     * Check if the response was truncated.
     */
    public boolean isTruncated() {
        return "length".equals(finishReason) || "max_tokens".equals(finishReason);
    }
}
