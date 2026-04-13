package com.nousresearch.hermes.tools.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nousresearch.hermes.tools.ToolEntry;
import com.nousresearch.hermes.tools.ToolRegistry;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Image generation tool.
 * Supports: DALL-E, Stability AI.
 */
public class ImageGenerationTool {
    private static final Logger logger = LoggerFactory.getLogger(ImageGenerationTool.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    
    private final OkHttpClient httpClient;
    private final String openaiKey;
    private final String stabilityKey;
    
    public ImageGenerationTool() {
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build();
        
        this.openaiKey = System.getenv("OPENAI_API_KEY");
        this.stabilityKey = System.getenv("STABILITY_API_KEY");
    }
    
    public void register(ToolRegistry registry) {
        registry.register(new ToolEntry.Builder()
            .name("image_generate")
            .toolset("image")
            .schema(Map.of("description", "Generate image from text prompt",
                "parameters", Map.of("type", "object",
                    "properties", Map.of(
                        "prompt", Map.of("type", "string"),
                        "size", Map.of("type", "string", "enum", List.of("1024x1024", "1792x1024", "1024x1792"), "default", "1024x1024"),
                        "quality", Map.of("type", "string", "enum", List.of("standard", "hd"), "default", "standard"),
                        "provider", Map.of("type", "string", "enum", List.of("openai", "stability"), "default", "openai"),
                        "output_path", Map.of("type", "string")),
                    "required", List.of("prompt"))))
            .handler(this::generateImage).emoji("🎨").build());
        
        registry.register(new ToolEntry.Builder()
            .name("image_edit")
            .toolset("image")
            .schema(Map.of("description", "Edit an image with a prompt",
                "parameters", Map.of("type", "object",
                    "properties", Map.of(
                        "image_path", Map.of("type", "string"),
                        "prompt", Map.of("type", "string"),
                        "mask_path", Map.of("type", "string")),
                    "required", List.of("image_path", "prompt"))))
            .handler(this::editImage).emoji("✏️").build());
    }
    
    private String generateImage(Map<String, Object> args) {
        String prompt = (String) args.get("prompt");
        String size = (String) args.getOrDefault("size", "1024x1024");
        String quality = (String) args.getOrDefault("quality", "standard");
        String provider = (String) args.getOrDefault("provider", "openai");
        String outputPath = (String) args.get("output_path");
        
        try {
            Path output = outputPath != null ? Path.of(outputPath)
                : Path.of(System.getProperty("java.io.tmpdir"), "image_" + System.currentTimeMillis() + ".png");
            
            byte[] image = switch (provider) {
                case "stability" -> generateWithStability(prompt, size);
                default -> generateWithOpenAI(prompt, size, quality);
            };
            
            Files.write(output, image);
            
            return ToolRegistry.toolResult(Map.of(
                "path", output.toString(),
                "provider", provider,
                "prompt", prompt,
                "size", size
            ));
            
        } catch (Exception e) {
            return ToolRegistry.toolError("Generation failed: " + e.getMessage());
        }
    }
    
    private byte[] generateWithOpenAI(String prompt, String size, String quality) throws Exception {
        if (openaiKey == null) {
            throw new IllegalStateException("OPENAI_API_KEY not set");
        }
        
        Map<String, Object> body = Map.of(
            "model", "dall-e-3",
            "prompt", prompt,
            "size", size,
            "quality", quality,
            "n", 1,
            "response_format", "b64_json"
        );
        
        Request request = new Request.Builder()
            .url("https://api.openai.com/v1/images/generations")
            .post(RequestBody.create(mapper.writeValueAsString(body), JSON))
            .header("Authorization", "Bearer " + openaiKey)
            .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new Exception("OpenAI API error: " + response.code());
            }
            
            var result = mapper.readTree(response.body().string());
            String b64 = result.path("data").get(0).path("b64_json").asText();
            return Base64.getDecoder().decode(b64);
        }
    }
    
    private byte[] generateWithStability(String prompt, String size) throws Exception {
        if (stabilityKey == null) {
            throw new IllegalStateException("STABILITY_API_KEY not set");
        }
        
        String[] dims = size.split("x");
        int width = Integer.parseInt(dims[0]);
        int height = Integer.parseInt(dims[1]);
        
        Map<String, Object> body = Map.of(
            "text_prompts", List.of(Map.of("text", prompt)),
            "cfg_scale", 7,
            "width", width,
            "height", height,
            "samples", 1,
            "steps", 30
        );
        
        Request request = new Request.Builder()
            .url("https://api.stability.ai/v1/generation/stable-diffusion-xl-1024-v1-0/text-to-image")
            .post(RequestBody.create(mapper.writeValueAsString(body), JSON))
            .header("Authorization", "Bearer " + stabilityKey)
            .header("Content-Type", "application/json")
            .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new Exception("Stability API error: " + response.code());
            }
            
            var result = mapper.readTree(response.body().string());
            String b64 = result.path("artifacts").get(0).path("base64").asText();
            return Base64.getDecoder().decode(b64);
        }
    }
    
    private String editImage(Map<String, Object> args) {
        return ToolRegistry.toolError("Image editing not yet implemented");
    }
}
