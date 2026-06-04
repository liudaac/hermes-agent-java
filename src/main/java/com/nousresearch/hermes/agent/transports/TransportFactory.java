package com.nousresearch.hermes.agent.transports;

import com.nousresearch.hermes.plugin.PluginManager;
import com.nousresearch.hermes.plugin.registry.ProviderRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Factory for creating transport instances.
 *
 * <p>Resolves transports via the plugin {@link ProviderRegistry} when available;
 * falls back to hardcoded type-based creation for backward compatibility.</p>
 */
public class TransportFactory {
    private static final Logger logger = LoggerFactory.getLogger(TransportFactory.class);

    private final Map<String, BaseTransport> transports = new HashMap<>();

    /**
     * Create a transport based on configuration.
     * Tries plugin registry first (by model match), falls back to type-based creation.
     */
    public BaseTransport createTransport(TransportConfig config) {
        // Strategy 1: route by model name through registered providers
        if (config.model() != null) {
            Optional<TransportProvider> match = resolveByModel(config.model());
            if (match.isPresent()) {
                logger.debug("Routed transport via plugin registry: model={} provider={}",
                        config.model(), match.get().getName());
                return match.get().createTransport(config);
            }
        }

        // Strategy 2: legacy hardcoded type dispatch
        return createByType(config);
    }

    /**
     * Find a TransportProvider that supports the given model.
     */
    @SuppressWarnings("unchecked")
    public Optional<TransportProvider> resolveByModel(String model) {
        if (model == null) return Optional.empty();
        PluginManager pm = PluginManager.getInstance();
        if (pm == null) return Optional.empty();
        ProviderRegistry<TransportProvider> registry =
                (ProviderRegistry<TransportProvider>) pm.getProviderRegistry("model_transport");
        if (registry == null) return Optional.empty();
        return registry.listAll().stream()
                .filter(p -> p.supportsModel(model))
                .findFirst();
    }

    /**
     * Look up a transport provider by name (e.g. "openai", "anthropic").
     */
    @SuppressWarnings("unchecked")
    public Optional<TransportProvider> resolveByName(String name) {
        if (name == null) return Optional.empty();
        PluginManager pm = PluginManager.getInstance();
        if (pm == null) return Optional.empty();
        ProviderRegistry<TransportProvider> registry =
                (ProviderRegistry<TransportProvider>) pm.getProviderRegistry("model_transport");
        if (registry == null) return Optional.empty();
        return registry.resolve(name);
    }

    private BaseTransport createByType(TransportConfig config) {
        return switch (config.type()) {
            case ANTHROPIC -> createAnthropicTransport(config);
            case BEDROCK -> createBedrockTransport(config);
            case CHAT_COMPLETIONS -> createChatCompletionsTransport(config);
            case CODEX -> createCodexTransport(config);
        };
    }

    public BaseTransport getTransport(String key, TransportConfig config) {
        return transports.computeIfAbsent(key, k -> createTransport(config));
    }

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
        return baseUrl != null ? new AnthropicTransport(apiKey, baseUrl) : new AnthropicTransport(apiKey);
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
        return baseUrl != null ? new ChatCompletionsTransport(apiKey, baseUrl) : new ChatCompletionsTransport(apiKey);
    }

    private BaseTransport createCodexTransport(TransportConfig config) {
        String apiKey = config.apiKey();
        String baseUrl = config.baseUrl();
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalArgumentException("Codex transport requires apiKey");
        }
        return baseUrl != null ? new CodexTransport(apiKey, baseUrl) : new CodexTransport(apiKey);
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
        String region,
        String model
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
            private String model;

            public Builder type(TransportType type) { this.type = type; return this; }
            public Builder apiKey(String apiKey) { this.apiKey = apiKey; return this; }
            public Builder baseUrl(String baseUrl) { this.baseUrl = baseUrl; return this; }
            public Builder accessKey(String accessKey) { this.accessKey = accessKey; return this; }
            public Builder secretKey(String secretKey) { this.secretKey = secretKey; return this; }
            public Builder region(String region) { this.region = region; return this; }
            public Builder model(String model) { this.model = model; return this; }

            public TransportConfig build() {
                return new TransportConfig(type, apiKey, baseUrl, accessKey, secretKey, region, model);
            }
        }
    }
}
