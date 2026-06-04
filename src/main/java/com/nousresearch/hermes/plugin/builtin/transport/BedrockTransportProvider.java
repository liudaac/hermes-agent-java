package com.nousresearch.hermes.plugin.builtin.transport;

import com.nousresearch.hermes.agent.transports.*;

import java.util.List;

/**
 * Built-in AWS Bedrock transport provider.
 */
public class BedrockTransportProvider implements TransportProvider {

    @Override
    public String getName() {
        return "bedrock";
    }

    @Override
    public String getLabel() {
        return "AWS Bedrock";
    }

    @Override
    public String getDefaultBaseUrl() {
        return null;  // Bedrock uses AWS SDK, not direct URL
    }

    @Override
    public List<String> getSupportedModels() {
        return List.of(
                "anthropic.claude-3-5-sonnet-20241022-v2:0",
                "anthropic.claude-3-haiku-20240307-v1:0",
                "meta.llama3-70b-instruct-v1:0",
                "mistral.mistral-large-2407-v1:0"
        );
    }

    @Override
    public boolean supportsModel(String model) {
        if (model == null) return false;
        return model.contains(".") && (model.startsWith("anthropic.")
                || model.startsWith("meta.")
                || model.startsWith("mistral.")
                || model.startsWith("amazon.")
                || model.startsWith("bedrock/"));
    }

    @Override
    public boolean isAvailable() {
        return System.getenv("AWS_ACCESS_KEY_ID") != null
                && System.getenv("AWS_SECRET_ACCESS_KEY") != null;
    }

    @Override
    public BaseTransport createTransport(TransportFactory.TransportConfig config) {
        String accessKey = config.accessKey() != null ? config.accessKey() : System.getenv("AWS_ACCESS_KEY_ID");
        String secretKey = config.secretKey() != null ? config.secretKey() : System.getenv("AWS_SECRET_ACCESS_KEY");
        String region = config.region() != null ? config.region()
                : (System.getenv("AWS_REGION") != null ? System.getenv("AWS_REGION") : "us-east-1");
        if (accessKey == null || secretKey == null) {
            throw new IllegalArgumentException("Bedrock transport requires AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY");
        }
        return new BedrockTransport(accessKey, secretKey, region);
    }

    @Override
    public TransportFactory.TransportConfig defaultConfig() {
        return TransportFactory.TransportConfig.builder()
                .type(TransportType.BEDROCK)
                .accessKey(System.getenv("AWS_ACCESS_KEY_ID"))
                .secretKey(System.getenv("AWS_SECRET_ACCESS_KEY"))
                .region(System.getenv("AWS_REGION"))
                .build();
    }
}
