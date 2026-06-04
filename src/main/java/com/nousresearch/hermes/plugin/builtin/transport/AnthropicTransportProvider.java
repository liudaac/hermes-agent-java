package com.nousresearch.hermes.plugin.builtin.transport;

import com.nousresearch.hermes.agent.transports.*;

import java.util.List;

/**
 * Built-in Anthropic Claude transport provider.
 */
public class AnthropicTransportProvider implements TransportProvider {

    @Override
    public String getName() {
        return "anthropic";
    }

    @Override
    public String getLabel() {
        return "Anthropic Claude";
    }

    @Override
    public String getDefaultBaseUrl() {
        return "https://api.anthropic.com";
    }

    @Override
    public List<String> getSupportedModels() {
        return List.of(
                "claude-3-5-sonnet-latest", "claude-3-5-sonnet-20241022",
                "claude-3-5-haiku-latest", "claude-3-5-haiku-20241022",
                "claude-3-opus-latest", "claude-3-opus-20240229",
                "claude-3-sonnet-20240229", "claude-3-haiku-20240307"
        );
    }

    @Override
    public boolean supportsModel(String model) {
        if (model == null) return false;
        String lower = model.toLowerCase();
        return lower.startsWith("claude-") || lower.startsWith("anthropic/");
    }

    @Override
    public boolean isAvailable() {
        return System.getenv("ANTHROPIC_API_KEY") != null;
    }

    @Override
    public BaseTransport createTransport(TransportFactory.TransportConfig config) {
        String apiKey = config.apiKey() != null ? config.apiKey() : System.getenv("ANTHROPIC_API_KEY");
        String baseUrl = config.baseUrl() != null ? config.baseUrl() : getDefaultBaseUrl();
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalArgumentException("Anthropic transport requires ANTHROPIC_API_KEY");
        }
        return new AnthropicTransport(apiKey, baseUrl);
    }

    @Override
    public TransportFactory.TransportConfig defaultConfig() {
        return TransportFactory.TransportConfig.builder()
                .type(TransportType.ANTHROPIC)
                .apiKey(System.getenv("ANTHROPIC_API_KEY"))
                .baseUrl(getDefaultBaseUrl())
                .build();
    }
}
