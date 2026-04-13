package com.nousresearch.hermes.compression;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nousresearch.hermes.config.ConfigManager;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Context compression service.
 * Compresses conversation history when it exceeds thresholds.
 */
public class CompressionService {
    private static final Logger logger = LoggerFactory.getLogger(CompressionService.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    
    private final OkHttpClient httpClient;
    private final ConfigManager config;
    private final String model;
    private final String apiKey;
    private final String baseUrl;
    
    public CompressionService() {
        this.config = ConfigManager.getInstance();
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();
        
        this.model = config.getString("compression.model", "google/gemini-flash-1.5");
        this.apiKey = config.getApiKey();
        this.baseUrl = config.getBaseUrl();
    }
    
    /**
     * Check if compression is needed.
     */
    public boolean needsCompression(int tokenCount) {
        int threshold = config.getInt("agent.compression_threshold", 4000);
        return tokenCount > threshold;
    }
    
    /**
     * Compress conversation history.
     */
    public String compress(String conversation) throws Exception {
        if (!config.getBoolean("compression.enabled", true)) {
            return conversation;
        }
        
        logger.info("Compressing conversation...");
        
        String prompt = buildCompressionPrompt(conversation);
        
        Map<String, Object> body = Map.of(
            "model", model,
            "messages", List.of(Map.of("role", "user", "content", prompt)),
            "max_tokens", 2000
        );
        
        Request request = new Request.Builder()
            .url(baseUrl + "/chat/completions")
            .post(RequestBody.create(mapper.writeValueAsString(body), JSON))
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new Exception("Compression failed: " + response.code());
            }
            
            var result = mapper.readTree(response.body().string());
            String compressed = result.path("choices").get(0).path("message").path("content").asText();
            
            logger.info("Compression complete: {} -> {} chars", conversation.length(), compressed.length());
            return compressed;
        }
    }
    
    /**
     * Summarize a section of conversation.
     */
    public String summarize(String text) throws Exception {
        String prompt = "Summarize the following conversation, preserving key facts and decisions:\n\n" + text;
        return compress(prompt);
    }
    
    private String buildCompressionPrompt(String conversation) {
        return """
            Compress the following conversation history while preserving:
            - Key facts and information
            - Important decisions made
            - Action items and todos
            - User preferences
            
            Remove:
            - Redundant exchanges
            - Filler content
            - Already-completed tasks
            
            Format as a concise summary.
            
            Conversation:
            """ + conversation;
    }
}
