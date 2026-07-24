package com.nousresearch.hermes.harness;

import com.nousresearch.hermes.model.ModelMessage;
import com.nousresearch.hermes.model.ToolCall;

import java.util.List;

/**
 * Immutable snapshot of loop state at the point where a tool call
 * requires approval.
 *
 * <p>Contains everything needed to resume execution after the approval
 * decision is made: the assistant message that produced the tool calls,
 * the list of all tool calls, which one is pending, results of already-
 * completed tool calls, and the loop state at that moment.</p>
 *
 * <p>This record replaces the inner class
 * {@code TenantAwareAIAgent.ToolApprovalCheckpoint}.</p>
 */
public record LoopCheckpoint(
    ModelMessage assistantMessage,
    List<ToolCall> toolCalls,
    int pendingIndex,
    List<ToolCallResult> completedResults,
    int historySize,
    int remainingIterations,
    int userTurnCount
) {
    /** Result of a single completed tool call (stored for resume). */
    public record ToolCallResult(String toolCallId, String result) {}

    /** The tool call that triggered the approval. */
    public ToolCall pendingToolCall() {
        return toolCalls.get(pendingIndex);
    }

    /** Number of tool calls remaining after the pending one. */
    public int remainingToolCalls() {
        return toolCalls.size() - pendingIndex - 1;
    }
}
