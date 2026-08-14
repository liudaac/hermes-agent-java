package com.nousresearch.hermes.harness.loop;

/**
 * Result of a single tool call execution.
 */
public record ToolCallResult(
    String callId,
    String content,
    boolean success,
    long durationMs,
    String toolName
) {
    public static ToolCallResult success(String callId, String content, long durationMs, String toolName) {
        return new ToolCallResult(callId, content, true, durationMs, toolName);
    }

    public static ToolCallResult failure(String callId, String error, long durationMs, String toolName) {
        return new ToolCallResult(callId, error, false, durationMs, toolName);
    }
}
