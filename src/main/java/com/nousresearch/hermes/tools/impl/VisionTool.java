package com.nousresearch.hermes.tools.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nousresearch.hermes.config.ConfigManager;
import com.nousresearch.hermes.tools.ToolRegistry;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Vision analysis tool for images.
 */
public class VisionTool {
    private static final Logger logger = LoggerFactory.getLogger(VisionTool.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    
    private final OkHttpClient httpClient;
    private final String provider;
    private final String apiKey;
    private final String baseUrl;
    
    public VisionTool() {
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();
        
        ConfigManager config = ConfigManager.getInstance();
        this.provider = config.getString("auxiliary.vision.provider", "auto");
        
        if ("auto".equals(provider)) {
            if (System.getenv("OPENAI_API_KEY") != null) {
                this.provider = "openai";
                this.apiKey = System.getenv("OPENAI_API_KEY");
                this.baseUrl = "https://api.openai.com/v1";
            } else {
                this.provider = "openrouter";
                this.apiKey = config.getApiKey();
                this.baseUrl = config.getBaseUrl();
            }
        } else {
            this.apiKey = System.getenv("OPENAI_API_KEY");
            this.baseUrl = "https://api.openai.com/v1";
        }
    }
    
    public void register(ToolRegistry registry) {
        registry.register(new ToolRegistry.Builder()
            .name("vision_analyze")
            .toolset("vision")
            .schema(Map.of("description", "Analyze an image",
                "parameters", Map.of("type", "object",
                    "properties", Map.of("image_url", Map.of("type", "string"), "prompt", Map.of("type", "string", "default", "Describe this image")),
                    "required", List.of("image_url"))))
            .handler(this::analyzeImage).emoji("👁️").build());
    }
    
    private String analyzeImage(Map<String, Object> args) {
        String imageUrl = (String) args.get("image_url");
        String prompt = (String) args.getOrDefault("prompt", "Describe this image");
        
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("model", "gpt-4o");
            
            var messages = mapper.createArrayNode();
            var message = mapper.createObjectNode();
            message.put("role", "user");
            
            var content = mapper.createArrayNode();
            content.add(Map.of("type", "text", "text", prompt));
            content.add(Map.of("type", "image_url", "image_url", Map.of("url", imageUrl)));
            
            message.set("content", content);
            messages.add(message);
            body.set("messages", messages);
            body.put("max_tokens", 1000);
            
            Request request = new Request.Builder()
                .url(baseUrl + "/chat/completions")
                .post(RequestBody.create(body.toString(), JSON))
                .header("Authorization", "Bearer " + apiKey)
                .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                JsonNode result = mapper.readTree(response.body().string());
                String analysis = result.path("choices").get(0).path("message").path("content").asText();
                return ToolRegistry.toolResult(Map.of("analysis", analysis));
            }
        } catch (Exception e) {
            return ToolRegistry.toolError("Failed: " + e.getMessage());
        }
    }
}
