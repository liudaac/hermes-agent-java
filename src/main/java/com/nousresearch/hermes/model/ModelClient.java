package com.nousresearch.hermes.model;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.nousresearch.hermes.config.HermesConfig;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * OpenAI-compatible LLM client.
 * Supports multiple providers: OpenAI, Anthropic, OpenRouter, local endpoints.
 */
public class ModelClient {
    private static final Logger logger = LoggerFactory.getLogger(ModelClient.class);
    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");
    
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
        JSONObject requestBody = new JSONObject();
        requestBody.put("model", model);
        requestBody.put("messages", messages);
        requestBody.put("tools", tools != null ? tools : List.of());
        requestBody.put("stream", stream);
        requestBody.put("temperature", 0.7);
        requestBody.put("max_tokens", 4096);
        
        RequestBody body = RequestBody.create(requestBody.toString(), JSON_MEDIA_TYPE);
        
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
            JSONObject root = JSON.parseObject(responseBody);
            
            return parseCompletionResponse(root);
        }
    }
    
    /**
     * Parse the completion response.
     */
    private ChatCompletionResponse parseCompletionResponse(JSONObject root) {
        ChatCompletionResponse result = new ChatCompletionResponse();
        
        JSONArray choices = root.getJSONArray("choices");
        if (choices != null && choices.size() > 0) {
            JSONObject firstChoice = choices.getJSONObject(0);
            JSONObject message = firstChoice.getJSONObject("message");
            
            if (message != null) {
                ModelMessage msg = new ModelMessage();
                msg.setRole(message.getString("role"));
                
                if (message.containsKey("content") && message.get("content") != null) {
                    msg.setContent(message.getString("content"));
                }
                
                // Parse tool calls
                if (message.containsKey("tool_calls")) {
                    JSONArray toolCallsArray = message.getJSONArray("tool_calls");
                    if (toolCallsArray != null) {
                        List<ToolCall> toolCalls = new ArrayList<>();
                        for (int i = 0; i < toolCallsArray.size(); i++) {
                            JSONObject tc = toolCallsArray.getJSONObject(i);
                            ToolCall toolCall = new ToolCall();
                            toolCall.setId(tc.getString("id"));
                            toolCall.setType(tc.getString("type"));
                            JSONObject functionObj = tc.getJSONObject("function");
                            if (functionObj != null) {
                                ToolCall.Function function = new ToolCall.Function();
                                function.setName(functionObj.getString("name"));
                                function.setArguments(functionObj.getString("arguments"));
                                toolCall.setFunction(function);
                            }
                            toolCalls.add(toolCall);
                        }
                        msg.setToolCalls(toolCalls);
                    }
                }
                
                result.setMessage(msg);
                result.setFinishReason(firstChoice.getString("finish_reason"));
            }
        }
        
        // Parse usage
        if (root.containsKey("usage")) {
            JSONObject usage = root.getJSONObject("usage");
            Usage usageInfo = new Usage();
            usageInfo.setPromptTokens(usage.getIntValue("prompt_tokens"));
            usageInfo.setCompletionTokens(usage.getIntValue("completion_tokens"));
            usageInfo.setTotalTokens(usage.getIntValue("total_tokens"));
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
