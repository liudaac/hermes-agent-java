package com.nousresearch.hermes.agent.transports;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Factory for creating transport instances.
 * Mirrors Python transport factory functionality.
 */
public class TransportFactory {
    private static final Logger logger = LoggerFactory.getLogger(TransportFactory.class);
    
    private final Map<String, BaseTransport> transports = new HashMap<>();
    
    /**
     * Create a transport based on configuration.
     */
    public BaseTransport createTransport(TransportConfig config) {
        return switch (config.type()) {
            case ANTHROPIC -> createAnthropicTransport(config);
            case BEDROCK -> createBedrockTransport(config);
            case CHAT_COMPLETIONS -> createChatCompletionsTransport(config);
            case CODEX -> createCodexTransport(config);
        };
    }
    
    /**
     * Get or create a cached transport.
     */
    public BaseTransport getTransport(String key, TransportConfig config) {
        return transports.computeIfAbsent(key, k -> createTransport(config));
    }
    
    /**
     * Close all cached transports.
     */
    public void closeAll() {
        for (BaseTransport transport : transports.values()) {
            try {
                transport.close();
            } catch (Exception e) {
                logger.error("Error closing transport", e);
            }
        }
        transports.clear();
    }
    
    private BaseTransport createAnthropicTransport(TransportConfig config) {
        String apiKey = config.apiKey();
        String baseUrl = config.baseUrl();
        
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalArgumentException("Anthropic transport requires apiKey");
        }
        
        return baseUrl != null ? 
            new AnthropicTransport(apiKey, baseUrl) : 
            new AnthropicTransport(apiKey);
    }
    
    private BaseTransport createBedrockTransport(TransportConfig config) {
        String accessKey = config.accessKey();
        String secretKey = config.secretKey();
        String region = config.region();
        
        if (accessKey == null || secretKey == null) {
            throw new IllegalArgumentException("Bedrock transport requires accessKey and secretKey");
        }
        
        return new BedrockTransport(accessKey, secretKey, region);
    }
    
    private BaseTransport createChatCompletionsTransport(TransportConfig config) {
        String apiKey = config.apiKey();
        String baseUrl = config.baseUrl();
        
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalArgumentException("ChatCompletions transport requires apiKey");
        }
        
        return baseUrl != null ? 
            new ChatCompletionsTransport(apiKey, baseUrl) : 
            new ChatCompletionsTransport(apiKey);
    }
    
    private BaseTransport createCodexTransport(TransportConfig config) {
        String apiKey = config.apiKey();
        String baseUrl = config.baseUrl();
        
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalArgumentException("Codex transport requires apiKey");
        }
        
        return baseUrl != null ? 
            new CodexTransport(apiKey, baseUrl) : 
            new CodexTransport(apiKey);
    }
    
    /**
     * Configuration for transport creation.
     */
    public record TransportConfig(
        TransportType type,
        String apiKey,
        String baseUrl,
        String accessKey,
        String secretKey,
        String region
    ) {
        public static Builder builder() {
            return new Builder();
        }
        
        public static class Builder {
            private TransportType type;
            private String apiKey;
            private String baseUrl;
            private String accessKey;
            private String secretKey;
            private String region;
            
            public Builder type(TransportType type) {
                this.type = type;
                return this;
            }
            
            public Builder apiKey(String apiKey) {
                this.apiKey = apiKey;
                return this;
            }
            
            public Builder baseUrl(String baseUrl) {
                this.baseUrl = baseUrl;
                return this;
            }
            
            public Builder accessKey(String accessKey) {
                this.accessKey = accessKey;
                return this;
            }
            
            public Builder secretKey(String secretKey) {
                this.secretKey = secretKey;
                return this;
            }
            
            public Builder region(String region) {
                this.region = region;
                return this;
            }
            
            public TransportConfig build() {
                return new TransportConfig(type, apiKey, baseUrl, accessKey, secretKey, region);
            }
        }
    }
}
