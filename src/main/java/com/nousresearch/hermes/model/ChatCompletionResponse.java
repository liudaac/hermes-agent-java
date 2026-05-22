package com.nousresearch.hermes.model;

import java.util.List;

/**
 * Response from a chat completion API call.
 */
public class ChatCompletionResponse {
    private final ModelMessage message;
    private final String finishReason;
    private final boolean hasToolCalls;
    private final String error;
    
    public ChatCompletionResponse(ModelMessage message, String finishReason, boolean hasToolCalls) {
        this.message = message;
        this.finishReason = finishReason;
        this.hasToolCalls = hasToolCalls;
        this.error = null;
    }
    
    private ChatCompletionResponse(String error) {
        this.message = null;
        this.finishReason = "error";
        this.hasToolCalls = false;
        this.error = error;
    }
    
    /**
     * Create an error response.
     */
    public static ChatCompletionResponse error(String message) {
        return new ChatCompletionResponse(message);
    }
    
    /**
     * Get the assistant's message.
     */
    public ModelMessage getMessage() {
        return message;
    }
    
    /**
     * Get the finish reason (e.g., "stop", "length", "tool_calls").
     */
    public String getFinishReason() {
        return finishReason;
    }
    
    /**
     * Check if the response includes tool calls.
     */
    public boolean hasToolCalls() {
        return hasToolCalls;
    }
    
    /**
     * Get the error message if the request failed.
     */
    public String getError() {
        return error;
    }
    
    /**
     * Check if the response is successful.
     */
    public boolean isSuccess() {
        return error == null && message != null;
    }
    
    /**
     * Get the content of the assistant's message.
     */
    public String getContent() {
        return message != null ? message.getContent() : null;
    }
    
    /**
     * Get tool calls from the message.
     */
    public List<ToolCall> getToolCalls() {
        return message != null ? message.getToolCalls() : null;
    }
    
    @Override
    public String toString() {
        if (error != null) {
            return "ChatCompletionResponse{error='" + error + "'}";
        }
        return "ChatCompletionResponse{message=" + message + ", finishReason='" + finishReason + "'}";
    }
}
