package com.nousresearch.hermes.agent.transports;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Stream;

/**
 * AWS Bedrock transport implementation.
 * Mirrors Python agent/transports/bedrock.py
 * 
 * Requires AWS SDK dependency:
 * <dependency>
 *     <groupId>software.amazon.awssdk</groupId>
 *     <artifactId>bedrockruntime</artifactId>
 *     <version>2.25.0</version>
 * </dependency>
 */
public class BedrockTransport implements BaseTransport {
    private static final Logger logger = LoggerFactory.getLogger(BedrockTransport.class);
    
    private final BedrockRuntimeClient client;
    private final String region;
    
    // Model ID mappings
    private static final Map<String, String> MODEL_MAPPINGS = Map.of(
        "claude-3-5-sonnet", "anthropic.claude-3-5-sonnet-20241022-v2:0",
        "claude-3-5-haiku", "anthropic.claude-3-5-haiku-20241022-v1:0",
        "claude-3-opus", "anthropic.claude-3-opus-20240229-v1:0",
        "claude-3-sonnet", "anthropic.claude-3-sonnet-20240229-v1:0",
        "claude-3-haiku", "anthropic.claude-3-haiku-20240307-v1:0"
    );
    
    public BedrockTransport(String accessKey, String secretKey, String region) {
        this.region = region != null ? region : "us-east-1";
        
        AwsBasicCredentials credentials = AwsBasicCredentials.create(accessKey, secretKey);
        this.client = BedrockRuntimeClient.builder()
            .region(Region.of(this.region))
            .credentialsProvider(StaticCredentialsProvider.create(credentials))
            .build();
    }
    
    @Override
    public TransportType getType() {
        return TransportType.BEDROCK;
    }
    
    @Override
    public Map<String, String> getHeaders() {
        // Bedrock uses AWS SDK, not HTTP headers directly
        return Collections.emptyMap();
    }
    
    @Override
    public boolean supportsModel(String model) {
        return model != null && (
            model.startsWith("claude-") ||
            MODEL_MAPPINGS.containsKey(model)
        );
    }
    
    private String resolveModelId(String model) {
        return MODEL_MAPPINGS.getOrDefault(model, model);
    }
    
    @Override
    public TransportResponse chat(
        List<TransportMessage> messages,
        List<ToolDefinition> tools,
        String model,
        Map<String, Object> options
    ) {
        try {
            String modelId = resolveModelId(model);
            
            // Build the request body for Anthropic models via Bedrock
            JSONObject requestBody = buildAnthropicRequestBody(messages, tools, options);
            
            InvokeModelRequest request = InvokeModelRequest.builder()
                .modelId(modelId)
                .body(SdkBytes.fromUtf8String(requestBody.toJSONString()))
                .contentType("application/json")
                .accept("application/json")
                .build();
            
            InvokeModelResponse response = client.invokeModel(request);
            String responseBody = response.body().asUtf8String();
            
            return parseAnthropicResponse(JSON.parseObject(responseBody));
            
        } catch (Exception e) {
            logger.error("Error in Bedrock chat", e);
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
        try {
            String modelId = resolveModelId(model);
            
            JSONObject requestBody = buildAnthropicRequestBody(messages, tools, options);
            requestBody.put("stream", true);
            
            InvokeModelWithResponseStreamRequest request = 
                InvokeModelWithResponseStreamRequest.builder()
                    .modelId(modelId)
                    .body(SdkBytes.fromUtf8String(requestBody.toJSONString()))
                    .contentType("application/json")
                    .accept("application/json")
                    .build();
            
            // For simplicity, we collect the stream into a single response
            // Full streaming would process ResponseStream chunks
            StringBuilder contentBuilder = new StringBuilder();
            List<TransportMessage.ToolCall> toolCalls = new ArrayList<>();
            
            client.invokeModelWithResponseStream(request)
                .body()
                .forEach(chunk -> {
                    String chunkJson = chunk.asUtf8String();
                    JSONObject chunkObj = JSON.parseObject(chunkJson);
                    
                    if (chunkObj.containsKey("completion")) {
                        contentBuilder.append(chunkObj.getString("completion"));
                    }
                    // Handle other chunk types as needed
                });
            
            return Stream.of(TransportResponse.success(
                contentBuilder.toString(),
                toolCalls.isEmpty() ? null : toolCalls,
                model,
                "stop",
                null, null, null,
                null
            ));
            
        } catch (Exception e) {
            logger.error("Error in Bedrock streaming chat", e);
            return Stream.of(TransportResponse.error(e.getMessage()));
        }
    }
    
    private JSONObject buildAnthropicRequestBody(
        List<TransportMessage> messages,
        List<ToolDefinition> tools,
        Map<String, Object> options
    ) {
        JSONObject body = new JSONObject();
        
        // Convert messages to Anthropic format
        JSONArray messagesArray = new JSONArray();
        String systemPrompt = null;
        
        for (TransportMessage msg : messages) {
            if ("system".equals(msg.role())) {
                systemPrompt = msg.content();
                continue;
            }
            
            JSONObject msgObj = new JSONObject();
            msgObj.put("role", msg.role());
            
            if (msg.toolCalls() != null && !msg.toolCalls().isEmpty()) {
                // Handle assistant with tool calls
                JSONArray content = new JSONArray();
                
                if (msg.content() != null && !msg.content().isEmpty()) {
                    JSONObject textBlock = new JSONObject();
                    textBlock.put("type", "text");
                    textBlock.put("text", msg.content());
                    content.add(textBlock);
                }
                
                for (TransportMessage.ToolCall call : msg.toolCalls()) {
                    JSONObject toolBlock = new JSONObject();
                    toolBlock.put("type", "tool_use");
                    toolBlock.put("id", call.getId());
                    toolBlock.put("name", call.getFunction().getName());
                    toolBlock.put("input", JSON.parseObject(call.getFunction().getArguments()));
                    content.add(toolBlock);
                }
                
                msgObj.put("content", content);
            } else if (msg.toolResult() != null) {
                // Handle tool result
                JSONArray content = new JSONArray();
                JSONObject resultBlock = new JSONObject();
                resultBlock.put("type", "tool_result");
                resultBlock.put("tool_use_id", msg.toolResult().getString("tool_call_id"));
                resultBlock.put("content", msg.toolResult().getString("content"));
                content.add(resultBlock);
                msgObj.put("content", content);
            } else {
                // Regular text message
                JSONArray content = new JSONArray();
                JSONObject textBlock = new JSONObject();
                textBlock.put("type", "text");
                textBlock.put("text", msg.content() != null ? msg.content() : "");
                content.add(textBlock);
                msgObj.put("content", content);
            }
            
            messagesArray.add(msgObj);
        }
        
        body.put("messages", messagesArray);
        
        if (systemPrompt != null) {
            body.put("system", systemPrompt);
        }
        
        // Add tools
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
        int maxTokens = 4096;
        if (options != null) {
            if (options.containsKey("max_tokens")) {
                maxTokens = (Integer) options.get("max_tokens");
            }
            if (options.containsKey("temperature")) {
                body.put("temperature", options.get("temperature"));
            }
            if (options.containsKey("top_p")) {
                body.put("top_p", options.get("top_p"));
            }
        }
        body.put("max_tokens", maxTokens);
        
        // Anthropic version for Bedrock
        body.put("anthropic_version", "bedrock-2023-05-31");
        
        return body;
    }
    
    private TransportResponse parseAnthropicResponse(JSONObject response) {
        String content = "";
        List<TransportMessage.ToolCall> toolCalls = new ArrayList<>();
        
        JSONArray contentArray = response.getJSONArray("content");
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
        
        JSONObject usage = response.getJSONObject("usage");
        Integer inputTokens = usage != null ? usage.getInteger("input_tokens") : null;
        Integer outputTokens = usage != null ? usage.getInteger("output_tokens") : null;
        
        return TransportResponse.success(
            content,
            toolCalls.isEmpty() ? null : toolCalls,
            response.getString("model"),
            response.getString("stop_reason"),
            inputTokens,
            outputTokens,
            inputTokens != null && outputTokens != null ? inputTokens + outputTokens : null,
            response
        );
    }
    
    @Override
    public void close() {
        client.close();
    }
}
