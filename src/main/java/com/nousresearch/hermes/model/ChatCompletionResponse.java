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
    private final TokenUsage usage;
    private final String model;

    public ChatCompletionResponse(ModelMessage message, String finishReason, boolean hasToolCalls) {
        this(message, finishReason, hasToolCalls, null, null);
    }

    public ChatCompletionResponse(ModelMessage message,
                                  String finishReason,
                                  boolean hasToolCalls,
                                  TokenUsage usage,
                                  String model) {
        this.message = message;
        this.finishReason = finishReason;
        this.hasToolCalls = hasToolCalls;
        this.error = null;
        this.usage = usage;
        this.model = model;
    }

    private ChatCompletionResponse(String error) {
        this.message = null;
        this.finishReason = "error";
        this.hasToolCalls = false;
        this.error = error;
        this.usage = null;
        this.model = null;
    }

    public static ChatCompletionResponse error(String message) {
        return new ChatCompletionResponse(message);
    }

    public ModelMessage getMessage() {
        return message;
    }

    public String getFinishReason() {
        return finishReason;
    }

    public boolean hasToolCalls() {
        return hasToolCalls;
    }

    public String getError() {
        return error;
    }

    public boolean isSuccess() {
        return error == null && message != null;
    }

    public String getContent() {
        return message != null ? message.getContent() : null;
    }

    public List<ToolCall> getToolCalls() {
        return message != null ? message.getToolCalls() : null;
    }

    public TokenUsage getUsage() {
        return usage;
    }

    public String getModel() {
        return model;
    }

    @Override
    public String toString() {
        if (error != null) {
            return "ChatCompletionResponse{error='" + error + "'}";
        }
        return "ChatCompletionResponse{message=" + message
            + ", finishReason='" + finishReason + "'"
            + ", model='" + model + "'"
            + ", usage=" + usage + "}";
    }

    /** Token usage reported by the model API. */
    public static final class TokenUsage {
        private final long promptTokens;
        private final long completionTokens;
        private final long cachedPromptTokens;
        private final long reasoningTokens;
        private final long totalTokens;

        public TokenUsage(long promptTokens,
                          long completionTokens,
                          long cachedPromptTokens,
                          long reasoningTokens,
                          long totalTokens) {
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
            this.cachedPromptTokens = cachedPromptTokens;
            this.reasoningTokens = reasoningTokens;
            this.totalTokens = totalTokens;
        }

        public long getPromptTokens() { return promptTokens; }
        public long getCompletionTokens() { return completionTokens; }
        public long getCachedPromptTokens() { return cachedPromptTokens; }
        public long getReasoningTokens() { return reasoningTokens; }
        public long getTotalTokens() { return totalTokens; }

        @Override
        public String toString() {
            return "TokenUsage{prompt=" + promptTokens
                + ", completion=" + completionTokens
                + ", cached=" + cachedPromptTokens
                + ", reasoning=" + reasoningTokens
                + ", total=" + totalTokens + "}";
        }
    }
}
