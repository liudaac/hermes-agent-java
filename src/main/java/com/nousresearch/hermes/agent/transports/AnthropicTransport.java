package com.nousresearch.hermes.agent.transports;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.nousresearch.hermes.utils.JsonUtils;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.stream.Stream;

/**
 * Anthropic API transport implementation.
 * Mirrors Python agent/transports/anthropic.py
 */
public class AnthropicTransport implements BaseTransport {
    private static final Logger logger = LoggerFactory.getLogger(AnthropicTransport.class);
    
    private static final String DEFAULT_BASE_URL = "https://api.anthropic.com";
    private static final String API_VERSION = "2023-06-01";
    
    private final OkHttpClient client;
    private final String apiKey;
    private final String baseUrl;
    private final Map<String, String> defaultHeaders;
    
    public AnthropicTransport(String apiKey) {
        this(apiKey, DEFAULT_BASE_URL);
    }
    
    public AnthropicTransport(String apiKey, String baseUrl) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl != null ? baseUrl : DEFAULT_BASE_URL;
        this.client = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofMinutes(5))
            .readTimeout(Duration.ofMinutes(10))
            .writeTimeout(Duration.ofMinutes(5))
            .build();
        
        this.defaultHeaders = new HashMap<>();
        this.defaultHeaders.put("x-api-key", apiKey);
        this.defaultHeaders.put("anthropic-version", API_VERSION);
        this.defaultHeaders.put("Content-Type", "application/json");
    }
    
    @Override
    public TransportType getType() {
        return TransportType.ANTHROPIC;
    }
    
    @Override
    public Map<String, String> getHeaders() {
        return new HashMap<>(defaultHeaders);
    }
    
    @Override
    public boolean supportsModel(String model) {
        return model != null && (
            model.startsWith("claude-") ||
            model.contains("anthropic")
        );
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
            Request request = buildRequest(requestBody, "/v1/messages");
            
            try (Response response = client.newCall(request).execute()) {
                return parseResponse(response);
            }
        } catch (Exception e) {
            logger.error("Error in Anthropic chat", e);
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
        
        // For streaming, we return a single response for now
        // Full streaming implementation would use ResponseBody.charStream()
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
                toolObj.put("name", tool.name());
                toolObj.put("description", tool.description());
                if (tool.parameters() != null) {
                    toolObj.put("input_schema", tool.parameters());
                }
                toolsArray.add(toolObj);
            }
            body.put("tools", toolsArray);
        }
        
        // Add options
        if (options != null) {
            if (options.containsKey("max_tokens")) {
                body.put("max_tokens", options.get("max_tokens"));
            } else {
                body.put("max_tokens", 4096);  // Default
            }
            
            if (options.containsKey("temperature")) {
                body.put("temperature", options.get("temperature"));
            }
            
            if (options.containsKey("system")) {
                body.put("system", options.get("system"));
            }
            
            if (options.containsKey("stream")) {
                body.put("stream", options.get("stream"));
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
            
            // Handle tool calls
            if (msg.toolCalls() != null && !msg.toolCalls().isEmpty()) {
                JSONArray toolCalls = new JSONArray();
                for (TransportMessage.ToolCall call : msg.toolCalls()) {
                    JSONObject callObj = new JSONObject();
                    callObj.put("id", call.getId());
                    callObj.put("type", call.getType());
                    if (call.getFunction() != null) {
                        callObj.put("name", call.getFunction().getName());
                        callObj.put("input", JSON.parseObject(call.getFunction().getArguments()));
                    }
                    toolCalls.add(callObj);
                }
                msgObj.put("content", toolCalls);
            }
            
            // Handle tool results
            if (msg.toolResult() != null) {
                JSONObject contentObj = new JSONObject();
                contentObj.put("type", "tool_result");
                contentObj.put("tool_use_id", msg.toolResult().getString("tool_call_id"));
                contentObj.put("content", msg.toolResult().getString("content"));
                JSONArray contentArray = new JSONArray();
                contentArray.add(contentObj);
                msgObj.put("content", contentArray);
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
            logger.error("Anthropic API error: {} - {}", response.code(), errorBody);
            return TransportResponse.error("API error " + response.code() + ": " + errorBody);
        }
        
        String responseBody = response.body().string();
        JSONObject json = JSON.parseObject(responseBody);
        
        // Parse content
        String content = "";
        List<TransportMessage.ToolCall> toolCalls = new ArrayList<>();
        
        JSONArray contentArray = json.getJSONArray("content");
        if (contentArray != null) {
            for (int i = 0; i < contentArray.size(); i++) {
                JSONObject item = contentArray.getJSONObject(i);
                String type = item.getString("type");
                
                if ("text".equals(type)) {
                    content += item.getString("text");
                } else if ("tool_use".equals(type)) {
                    TransportMessage.ToolFunction function = new TransportMessage.ToolFunction(
                        item.getString("name"),
                        item.getJSONObject("input") != null ? 
                            item.getJSONObject("input").toJSONString() : "{}"
                    );
                    toolCalls.add(new TransportMessage.ToolCall(
                        item.getString("id"),
                        "function",
                        function
                    ));
                }
            }
        }
        
        // Parse usage
        JSONObject usage = json.getJSONObject("usage");
        Integer promptTokens = usage != null ? usage.getInteger("input_tokens") : null;
        Integer completionTokens = usage != null ? usage.getInteger("output_tokens") : null;
        
        return TransportResponse.success(
            content,
            toolCalls.isEmpty() ? null : toolCalls,
            json.getString("model"),
            json.getString("stop_reason"),
            promptTokens,
            completionTokens,
            promptTokens != null && completionTokens != null ? 
                promptTokens + completionTokens : null,
            json
        );
    }
    
    @Override
    public void close() {
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
    }
}
