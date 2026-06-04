package com.nousresearch.hermes.plugin.builtin.transport;

import com.nousresearch.hermes.agent.transports.*;

import java.util.List;

/**
 * Built-in OpenAI / ChatCompletions transport provider.
 * Handles GPT-3.5, GPT-4, GPT-4o, and OpenAI-compatible endpoints.
 */
public class OpenAITransportProvider implements TransportProvider {

    @Override
    public String getName() {
        return "openai";
    }

    @Override
    public String getLabel() {
        return "OpenAI";
    }

    @Override
    public String getDefaultBaseUrl() {
        return "https://api.openai.com/v1";
    }

    @Override
    public List<String> getSupportedModels() {
        return List.of(
                "gpt-3.5-turbo", "gpt-3.5-turbo-16k",
                "gpt-4", "gpt-4-turbo", "gpt-4-turbo-preview",
                "gpt-4o", "gpt-4o-mini", "gpt-4o-2024-05-13",
                "o1-preview", "o1-mini"
        );
    }

    @Override
    public boolean supportsModel(String model) {
        if (model == null) return false;
        String lower = model.toLowerCase();
        return lower.startsWith("gpt-") || lower.startsWith("o1-") || lower.startsWith("openai/");
    }

    @Override
    public boolean isAvailable() {
        return System.getenv("OPENAI_API_KEY") != null;
    }

    @Override
    public BaseTransport createTransport(TransportFactory.TransportConfig config) {
        String apiKey = config.apiKey() != null ? config.apiKey() : System.getenv("OPENAI_API_KEY");
        String baseUrl = config.baseUrl() != null ? config.baseUrl() : getDefaultBaseUrl();
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalArgumentException("OpenAI transport requires OPENAI_API_KEY");
        }
        return new ChatCompletionsTransport(apiKey, baseUrl);
    }

    @Override
    public TransportFactory.TransportConfig defaultConfig() {
        return TransportFactory.TransportConfig.builder()
                .type(TransportType.CHAT_COMPLETIONS)
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .baseUrl(getDefaultBaseUrl())
                .build();
    }
}
