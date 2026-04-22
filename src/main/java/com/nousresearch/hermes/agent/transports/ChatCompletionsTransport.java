package com.nousresearch.hermes.agent.transports;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.stream.Stream;

/**
 * OpenAI Chat Completions API transport implementation.
 * Mirrors Python agent/transports/chat_completions.py
 * Compatible with OpenAI, Azure OpenAI, and compatible providers.
 */
public class ChatCompletionsTransport implements BaseTransport {
    private static final Logger logger = LoggerFactory.getLogger(ChatCompletionsTransport.class);
    
    private static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";
    
    private final OkHttpClient client;
    private final String apiKey;
    private final String baseUrl;
    private final Map<String, String> defaultHeaders;
    
    public ChatCompletionsTransport(String apiKey) {
        this(apiKey, DEFAULT_BASE_URL);
    }
    
    public ChatCompletionsTransport(String apiKey, String baseUrl) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl != null ? baseUrl : DEFAULT_BASE_URL;
        this.client = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofMinutes(5))
            .readTimeout(Duration.ofMinutes(10))
            .writeTimeout(Duration.ofMinutes(5))
            .build();
        
        this.defaultHeaders = new HashMap<>();
        this.defaultHeaders.put("Authorization", "Bearer " + apiKey);
        this.defaultHeaders.put("Content-Type", "application/json");
    }
    
    @Override
    public TransportType getType() {
        return TransportType.CHAT_COMPLETIONS;
    }
    
    @Override
    public Map<String, String> getHeaders() {
        return new HashMap<>(defaultHeaders);
    }
    
    @Override
    public boolean supportsModel(String model) {
        // Chat completions supports most models
        return model != null && !model.startsWith("claude-");
    }
    
    @Override
    public TransportResponse chat(
        List<TransportMessage> messages,
        List<ToolDefinition> tools,
        String model,
        Map<String, Object> options
    ) {
        try {
            JSONObject requestBody = buildRequestBody(messages, tools, model, options);
            Request request = buildRequest(requestBody, "/chat/completions");
            
            try (Response response = client.newCall(request).execute()) {
                return parseResponse(response);
            }
        } catch (Exception e) {
            logger.error("Error in ChatCompletions chat", e);
            return TransportResponse.error(e.getMessage());
        }
    }
    
    @Override
    public Stream<TransportResponse> chatStream(
        List<TransportMessage> messages,
        List<ToolDefinition> tools,
        String model,
        Map<String, Object> options
    ) {
        Map<String, Object> streamOptions = new HashMap<>(options != null ? options : Collections.emptyMap());
        streamOptions.put("stream", true);
        
        // For streaming, return single response for now
        return Stream.of(chat(messages, tools, model, streamOptions));
    }
    
    private JSONObject buildRequestBody(
        List<TransportMessage> messages,
        List<ToolDefinition> tools,
        String model,
        Map<String, Object> options
    ) {
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("messages", convertMessages(messages));
        
        // Add tools if provided
        if (tools != null && !tools.isEmpty()) {
            JSONArray toolsArray = new JSONArray();
            for (ToolDefinition tool : tools) {
                JSONObject toolObj = new JSONObject();
                toolObj.put("type", "function");
                JSONObject function = new JSONObject();
                function.put("name", tool.name());
                function.put("description", tool.description());
                if (tool.parameters() != null) {
                    function.put("parameters", tool.parameters());
                }
                toolObj.put("function", function);
                toolsArray.add(toolObj);
            }
            body.put("tools", toolsArray);
        }
        
        // Add options
        if (options != null) {
            if (options.containsKey("max_tokens")) {
                body.put("max_tokens", options.get("max_tokens"));
            }
            if (options.containsKey("temperature")) {
                body.put("temperature", options.get("temperature"));
            }
            if (options.containsKey("top_p")) {
                body.put("top_p", options.get("top_p"));
            }
            if (options.containsKey("stream")) {
                body.put("stream", options.get("stream"));
            }
            if (options.containsKey("response_format")) {
                body.put("response_format", options.get("response_format"));
            }
        }
        
        return body;
    }
    
    private JSONArray convertMessages(List<TransportMessage> messages) {
        JSONArray result = new JSONArray();
        for (TransportMessage msg : messages) {
            JSONObject msgObj = new JSONObject();
            msgObj.put("role", msg.role());
            
            if (msg.content() != null) {
                msgObj.put("content", msg.content());
            }
            
            // Handle tool calls (assistant message)
            if (msg.toolCalls() != null && !msg.toolCalls().isEmpty()) {
                JSONArray toolCalls = new JSONArray();
                for (TransportMessage.ToolCall call : msg.toolCalls()) {
                    JSONObject callObj = new JSONObject();
                    callObj.put("id", call.getId());
                    callObj.put("type", call.getType());
                    JSONObject function = new JSONObject();
                    function.put("name", call.getFunction().getName());
                    function.put("arguments", call.getFunction().getArguments());
                    callObj.put("function", function);
                    toolCalls.add(callObj);
                }
                msgObj.put("tool_calls", toolCalls);
            }
            
            // Handle tool results
            if (msg.toolResult() != null) {
                msgObj.put("role", "tool");
                msgObj.put("tool_call_id", msg.toolResult().getString("tool_call_id"));
                msgObj.put("content", msg.toolResult().getString("content"));
            }
            
            result.add(msgObj);
        }
        return result;
    }
    
    private Request buildRequest(JSONObject body, String path) {
        RequestBody requestBody = RequestBody.create(
            body.toJSONString(),
            MediaType.parse("application/json")
        );
        
        Request.Builder builder = new Request.Builder()
            .url(baseUrl + path)
            .post(requestBody);
        
        defaultHeaders.forEach(builder::header);
        
        return builder.build();
    }
    
    private TransportResponse parseResponse(Response response) throws IOException {
        if (!response.isSuccessful()) {
            String errorBody = response.body() != null ? response.body().string() : "Unknown error";
            logger.error("ChatCompletions API error: {} - {}", response.code(), errorBody);
            return TransportResponse.error("API error " + response.code() + ": " + errorBody);
        }
        
        String responseBody = response.body().string();
        JSONObject json = JSON.parseObject(responseBody);
        
        JSONArray choices = json.getJSONArray("choices");
        if (choices == null || choices.isEmpty()) {
            return TransportResponse.error("No choices in response");
        }
        
        JSONObject choice = choices.getJSONObject(0);
        JSONObject message = choice.getJSONObject("message");
        
        String content = message.getString("content");
        
        // Parse tool calls
        List<TransportMessage.ToolCall> toolCalls = new ArrayList<>();
        JSONArray toolCallsArray = message.getJSONArray("tool_calls");
        if (toolCallsArray != null) {
            for (int i = 0; i < toolCallsArray.size(); i++) {
                JSONObject callObj = toolCallsArray.getJSONObject(i);
                JSONObject function = callObj.getJSONObject("function");
                
                TransportMessage.ToolFunction toolFunc = new TransportMessage.ToolFunction(
                    function.getString("name"),
                    function.getString("arguments")
                );
                toolCalls.add(new TransportMessage.ToolCall(
                    callObj.getString("id"),
                    callObj.getString("type"),
                    toolFunc
                ));
            }
        }
        
        // Parse usage
        JSONObject usage = json.getJSONObject("usage");
        Integer promptTokens = usage != null ? usage.getInteger("prompt_tokens") : null;
        Integer completionTokens = usage != null ? usage.getInteger("completion_tokens") : null;
        Integer totalTokens = usage != null ? usage.getInteger("total_tokens") : null;
        
        return TransportResponse.success(
            content,
            toolCalls.isEmpty() ? null : toolCalls,
            json.getString("model"),
            choice.getString("finish_reason"),
            promptTokens,
            completionTokens,
            totalTokens,
            json
        );
    }
    
    @Override
    public void close() {
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
    }
}
