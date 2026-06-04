package com.nousresearch.hermes.tools.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * OpenAI DALL-E image generation provider.
 */
public class OpenAIImageGenProvider implements ImageGenProvider {
    private static final Logger logger = LoggerFactory.getLogger(OpenAIImageGenProvider.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final String BASE_URL = "https://api.openai.com/v1/images/generations";

    private final OkHttpClient httpClient;
    private final String apiKey;

    public OpenAIImageGenProvider() {
        this(System.getenv("OPENAI_API_KEY"));
    }

    public OpenAIImageGenProvider(String apiKey) {
        this.apiKey = apiKey;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public String getName() {
        return "openai";
    }

    @Override
    public boolean isAvailable() {
        return apiKey != null && !apiKey.isEmpty();
    }

    @Override
    public byte[] generate(String prompt, String size, String quality, Map<String, Object> options) throws Exception {
        if (!isAvailable()) {
            throw new IllegalStateException("OPENAI_API_KEY not set");
        }

        Map<String, Object> body = Map.of(
                "model", "dall-e-3",
                "prompt", prompt,
                "size", size != null ? size : "1024x1024",
                "quality", quality != null ? quality : "standard",
                "n", 1,
                "response_format", "b64_json"
        );

        Request request = new Request.Builder()
                .url(BASE_URL)
                .post(RequestBody.create(mapper.writeValueAsString(body), JSON))
                .header("Authorization", "Bearer " + apiKey)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new Exception("OpenAI image generation failed: " + response.code());
            }
            var json = mapper.readTree(response.body().string());
            String b64 = json.path("data").get(0).path("b64_json").asText();
            return java.util.Base64.getDecoder().decode(b64);
        }
    }

    @Override
    public byte[] edit(String imagePath, String prompt, String maskPath, Map<String, Object> options) throws Exception {
        // DALL-E edit not implemented; fall back to generate
        logger.warn("OpenAI image edit not implemented, falling back to generate");
        return generate(prompt, "1024x1024", "standard", options);
    }
}
