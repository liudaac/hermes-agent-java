package com.nousresearch.hermes.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nousresearch.hermes.config.HermesConfig;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * OpenAI-compatible LLM client.
 * Supports multiple providers: OpenAI, Anthropic, OpenRouter, local endpoints.
 */
public class ModelClient {
    private static final Logger logger = LoggerFactory.getLogger(ModelClient.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    
    private final HermesConfig config;
    private final OkHttpClient httpClient;
    
    public ModelClient(HermesConfig config) {
        this.config = config;
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build();
    }
    
    /**
     * Send a chat completion request.
     */
    public ChatCompletionResponse chatCompletion(
            List<ModelMessage> messages,
            List<Map<String, Object>> tools,
            boolean stream) throws IOException {
        
        String baseUrl = config.getBaseUrl();
        String apiKey = config.getApiKey();
        String model = config.getCurrentModel();
        
        // Build request body
        Map<String, Object> requestBody = Map.of(
            "model", model,
            "messages", messages,
            "tools", tools != null ? tools : List.of(),
            "stream", stream,
            "temperature", 0.7,
            "max_tokens", 4096
        );
        
        String json = mapper.writeValueAsString(requestBody);
        RequestBody body = RequestBody.create(json, JSON);
        
        Request.Builder requestBuilder = new Request.Builder()
            .url(baseUrl + "/chat/completions")
            .post(body)
            .header("Content-Type", "application/json");
        
        if (apiKey != null && !apiKey.isEmpty()) {
            requestBuilder.header("Authorization", "Bearer " + apiKey);
        }
        
        // Add provider-specific headers
        String provider = config.getProvider();
        if ("openrouter".equals(provider)) {
            requestBuilder.header("HTTP-Referer", "https://hermes-agent.nousresearch.com");
            requestBuilder.header("X-Title", "Hermes Agent");
        }
        
        Request request = requestBuilder.build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "Unknown error";
                throw new IOException("API request failed: " + response.code() + " - " + errorBody);
            }
            
            String responseBody = response.body().string();
            JsonNode root = mapper.readTree(responseBody);
            
            return parseCompletionResponse(root);
        }
    }
    
    /**
     * Parse the completion response.
     */
    private ChatCompletionResponse parseCompletionResponse(JsonNode root) {
        ChatCompletionResponse result = new ChatCompletionResponse();
        
        JsonNode choices = root.get("choices");
        if (choices != null && choices.isArray() && choices.size() > 0) {
            JsonNode firstChoice = choices.get(0);
            JsonNode message = firstChoice.get("message");
            
            if (message != null) {
                ModelMessage msg = new ModelMessage();
                msg.setRole(message.get("role").asText());
                
                if (message.has("content") && !message.get("content").isNull()) {
                    msg.setContent(message.get("content").asText());
                }
                
                // Parse tool calls
                if (message.has("tool_calls") && message.get("tool_calls").isArray()) {
                    List<ToolCall> toolCalls = mapper.convertValue(
                        message.get("tool_calls"),
                        mapper.getTypeFactory().constructCollectionType(List.class, ToolCall.class)
                    );
                    msg.setToolCalls(toolCalls);
                }
                
                result.setMessage(msg);
                result.setFinishReason(firstChoice.get("finish_reason").asText());
            }
        }
        
        // Parse usage
        if (root.has("usage")) {
            JsonNode usage = root.get("usage");
            Usage usageInfo = new Usage();
            usageInfo.setPromptTokens(usage.get("prompt_tokens").asInt());
            usageInfo.setCompletionTokens(usage.get("completion_tokens").asInt());
            usageInfo.setTotalTokens(usage.get("total_tokens").asInt());
            result.setUsage(usageInfo);
        }
        
        return result;
    }
    
    /**
     * Test the connection to the model API.
     */
    public boolean testConnection() {
        try {
            List<ModelMessage> messages = List.of(
                ModelMessage.system("You are a helpful assistant."),
                ModelMessage.user("Hi")
            );
            chatCompletion(messages, null, false);
            return true;
        } catch (Exception e) {
            logger.warn("Connection test failed: {}", e.getMessage());
            return false;
        }
    }
    
    // Inner classes
    public static class ChatCompletionResponse {
        private ModelMessage message;
        private String finishReason;
        private Usage usage;
        
        public ModelMessage getMessage() { return message; }
        public void setMessage(ModelMessage message) { this.message = message; }
        
        public String getFinishReason() { return finishReason; }
        public void setFinishReason(String finishReason) { this.finishReason = finishReason; }
        
        public Usage getUsage() { return usage; }
        public void setUsage(Usage usage) { this.usage = usage; }
        
        public boolean hasToolCalls() {
            return message != null && message.getToolCalls() != null && !message.getToolCalls().isEmpty();
        }
    }
    
    public static class Usage {
        private int promptTokens;
        private int completionTokens;
        private int totalTokens;
        
        public int getPromptTokens() { return promptTokens; }
        public void setPromptTokens(int promptTokens) { this.promptTokens = promptTokens; }
        
        public int getCompletionTokens() { return completionTokens; }
        public void setCompletionTokens(int completionTokens) { this.completionTokens = completionTokens; }
        
        public int getTotalTokens() { return totalTokens; }
        public void setTotalTokens(int totalTokens) { this.totalTokens = totalTokens; }
    }
}
