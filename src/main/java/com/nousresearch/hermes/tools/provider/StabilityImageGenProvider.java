package com.nousresearch.hermes.tools.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Stability AI image generation provider.
 */
public class StabilityImageGenProvider implements ImageGenProvider {
    private static final Logger logger = LoggerFactory.getLogger(StabilityImageGenProvider.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final String BASE_URL = "https://api.stability.ai/v2beta/stable-image/generate/core";

    private final OkHttpClient httpClient;
    private final String apiKey;

    public StabilityImageGenProvider() {
        this(System.getenv("STABILITY_API_KEY"));
    }

    public StabilityImageGenProvider(String apiKey) {
        this.apiKey = apiKey;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public String getName() {
        return "stability";
    }

    @Override
    public boolean isAvailable() {
        return apiKey != null && !apiKey.isEmpty();
    }

    @Override
    public byte[] generate(String prompt, String size, String quality, Map<String, Object> options) throws Exception {
        if (!isAvailable()) {
            throw new IllegalStateException("STABILITY_API_KEY not set");
        }

        // Stability uses form-data
        MultipartBody.Builder bodyBuilder = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("prompt", prompt)
                .addFormDataPart("output_format", "png");

        Request request = new Request.Builder()
                .url(BASE_URL)
                .post(bodyBuilder.build())
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "image/*")
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new Exception("Stability AI generation failed: " + response.code());
            }
            return response.body().bytes();
        }
    }

    @Override
    public byte[] edit(String imagePath, String prompt, String maskPath, Map<String, Object> options) throws Exception {
        throw new UnsupportedOperationException("Stability image edit not yet implemented");
    }
}
