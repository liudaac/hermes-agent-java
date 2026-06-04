package com.nousresearch.hermes.plugin.builtin.transport;

import com.nousresearch.hermes.agent.transports.*;

import java.util.List;

/**
 * Built-in Codex / OpenAI Responses API transport provider.
 */
public class CodexTransportProvider implements TransportProvider {

    @Override
    public String getName() {
        return "codex";
    }

    @Override
    public String getLabel() {
        return "OpenAI Codex / Responses";
    }

    @Override
    public String getDefaultBaseUrl() {
        return "https://api.openai.com/v1";
    }

    @Override
    public List<String> getSupportedModels() {
        return List.of("gpt-5", "gpt-5.5", "codex-mini", "codex");
    }

    @Override
    public boolean supportsModel(String model) {
        if (model == null) return false;
        String lower = model.toLowerCase();
        return lower.startsWith("codex") || lower.startsWith("gpt-5") || lower.startsWith("o3-");
    }

    @Override
    public boolean isAvailable() {
        return System.getenv("OPENAI_API_KEY") != null
                || System.getenv("CODEX_API_KEY") != null;
    }

    @Override
    public BaseTransport createTransport(TransportFactory.TransportConfig config) {
        String apiKey = config.apiKey();
        if (apiKey == null) apiKey = System.getenv("CODEX_API_KEY");
        if (apiKey == null) apiKey = System.getenv("OPENAI_API_KEY");
        String baseUrl = config.baseUrl() != null ? config.baseUrl() : getDefaultBaseUrl();
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalArgumentException("Codex transport requires OPENAI_API_KEY or CODEX_API_KEY");
        }
        return new CodexTransport(apiKey, baseUrl);
    }

    @Override
    public TransportFactory.TransportConfig defaultConfig() {
        String apiKey = System.getenv("CODEX_API_KEY");
        if (apiKey == null) apiKey = System.getenv("OPENAI_API_KEY");
        return TransportFactory.TransportConfig.builder()
                .type(TransportType.CODEX)
                .apiKey(apiKey)
                .baseUrl(getDefaultBaseUrl())
                .build();
    }
}
