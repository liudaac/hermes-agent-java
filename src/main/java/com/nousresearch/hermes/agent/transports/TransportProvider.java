package com.nousresearch.hermes.agent.transports;

import com.nousresearch.hermes.plugin.registry.NamedProvider;

import java.util.List;

/**
 * Plugin-friendly Provider interface for model transports.
 * Allows third-party transports (e.g. local Ollama, custom OpenAI-compatible endpoints)
 * to be registered as plugins.
 *
 * <p>Implementations are registered into the "model_transport" category of
 * {@link com.nousresearch.hermes.plugin.registry.ProviderRegistry}.</p>
 */
public interface TransportProvider extends NamedProvider {

    /**
     * Provider name (must be unique). Used as lookup key for "model.provider" config.
     * Example: "openai", "anthropic", "ollama".
     */
    @Override
    String getName();

    /**
     * Human-readable label for UI display.
     */
    String getLabel();

    /**
     * Default base URL for this provider (may be overridden by user config).
     */
    String getDefaultBaseUrl();

    /**
     * List of canonical model identifiers this provider supports.
     * Used for routing (e.g. "claude-3-5-sonnet" → AnthropicProvider).
     * Return empty list if you match by prefix or pattern in {@link #supportsModel}.
     */
    List<String> getSupportedModels();

    /**
     * Whether this provider handles the given model identifier.
     */
    boolean supportsModel(String model);

    /**
     * Check whether this provider is properly configured (e.g. API key set).
     */
    boolean isAvailable();

    /**
     * Create a transport instance with the given configuration.
     *
     * @param config transport configuration (may include api_key, base_url, etc.)
     * @return BaseTransport instance ready to use
     */
    BaseTransport createTransport(TransportFactory.TransportConfig config);

    /**
     * Build a default TransportConfig from environment variables.
     * Used when no explicit config is provided.
     */
    default TransportFactory.TransportConfig defaultConfig() {
        return TransportFactory.TransportConfig.builder().build();
    }
}
