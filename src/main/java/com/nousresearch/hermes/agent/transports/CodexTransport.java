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
 * OpenAI Codex Responses API transport implementation.
 * Mirrors Python agent/transports/codex.py
 * Uses the new OpenAI Responses API for agentic workflows.
 */
public class CodexTransport implements BaseTransport {
    private static final Logger logger = LoggerFactory.getLogger(CodexTransport.class);
    
    private static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";
    
    private final OkHttpClient client;
    private final String apiKey;
    private final String baseUrl;
    private final Map<String, String> defaultHeaders;
    
    public CodexTransport(String apiKey) {
        this(apiKey, DEFAULT_BASE_URL);
    }
    
    public CodexTransport(String apiKey, String baseUrl) {
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
        return TransportType.CODEX;
    }
    
    @Override
    public Map<String, String> getHeaders() {
        return new HashMap<>(defaultHeaders);
    }
    
    @Override
    public boolean supportsModel(String model) {
        // Codex transport is specifically for OpenAI models
        return model != null && (
            model.startsWith("gpt-") ||
            model.startsWith("o1") ||
            model.startsWith("o3") ||
            model.contains("codex")
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
            Request request = buildRequest(requestBody, "/responses");
            
            try (Response response = client.newCall(request).execute()) {
                return parseResponse(response);
            }
        } catch (Exception e) {
            logger.error("Error in Codex chat", e);
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
        
        return Stream.of(chat(messages, tools, model, streamOptions));
    }
    
    private JSONObject buildRequestBody(
        List<TransportMessage> messages,
        List<ToolDefinition> tools,
        String model,
        Map<String, Object> options
    ) {
        JSONObject body = new JSONObject();
        
        // Map model name if needed
        body.put("model", model);
        body.put("input", convertInput(messages));
        
        // Add tools if provided (as functions in Responses API)
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
            body.put("tool_choice", "auto");
        }
        
        // Add options
        if (options != null) {
            if (options.containsKey("max_output_tokens")) {
                body.put("max_output_tokens", options.get("max_output_tokens"));
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
            if (options.containsKey("truncation")) {
                body.put("truncation", options.get("truncation"));
            }
            if (options.containsKey("previous_response_id")) {
                body.put("previous_response_id", options.get("previous_response_id"));
            }
        }
        
        return body;
    }
    
    private JSONArray convertInput(List<TransportMessage> messages) {
        JSONArray result = new JSONArray();
        for (TransportMessage msg : messages) {
            JSONObject msgObj = new JSONObject();
            msgObj.put("role", msg.role());
            
            if (msg.content() != null) {
                msgObj.put("content", msg.content());
            }
            
            // Handle tool calls (assistant message with tool calls)
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
                JSONObject content = new JSONObject();
                content.put("type", "tool_result");
                content.put("tool_use_id", msg.toolResult().getString("tool_call_id"));
                content.put("output", msg.toolResult().getString("content"));
                
                JSONArray contentArray = new JSONArray();
                contentArray.add(content);
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
            logger.error("Codex API error: {} - {}", response.code(), errorBody);
            return TransportResponse.error("API error " + response.code() + ": " + errorBody);
        }
        
        String responseBody = response.body().string();
        JSONObject json = JSON.parseObject(responseBody);
        
        // Parse output
        String content = "";
        List<TransportMessage.ToolCall> toolCalls = new ArrayList<>();
        
        JSONArray output = json.getJSONArray("output");
        if (output != null) {
            for (int i = 0; i < output.size(); i++) {
                JSONObject item = output.getJSONObject(i);
                String type = item.getString("type");
                
                if ("message".equals(type)) {
                    JSONArray contentArray = item.getJSONArray("content");
                    if (contentArray != null) {
                        for (int j = 0; j < contentArray.size(); j++) {
                            JSONObject contentItem = contentArray.getJSONObject(j);
                            if ("text".equals(contentItem.getString("type"))) {
                                content += contentItem.getString("text");
                            }
                        }
                    }
                } else if ("function_call".equals(type) || "tool_use".equals(type)) {
                    TransportMessage.ToolFunction function = new TransportMessage.ToolFunction(
                        item.getString("name"),
                        item.getJSONObject("arguments") != null ? 
                            item.getJSONObject("arguments").toJSONString() : "{}"
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
        Integer inputTokens = usage != null ? usage.getInteger("input_tokens") : null;
        Integer outputTokens = usage != null ? usage.getInteger("output_tokens") : null;
        Integer totalTokens = usage != null ? usage.getInteger("total_tokens") : null;
        
        return TransportResponse.success(
            content,
            toolCalls.isEmpty() ? null : toolCalls,
            json.getString("model"),
            json.getString("status"),
            inputTokens,
            outputTokens,
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
