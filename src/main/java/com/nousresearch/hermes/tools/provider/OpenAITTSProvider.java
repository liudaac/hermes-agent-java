package com.nousresearch.hermes.tools.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * OpenAI TTS provider.
 */
public class OpenAITTSProvider implements TTSProvider {
    private static final Logger logger = LoggerFactory.getLogger(OpenAITTSProvider.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final String BASE_URL = "https://api.openai.com/v1/audio/speech";

    private final OkHttpClient httpClient;
    private final String apiKey;

    public OpenAITTSProvider() {
        this(System.getenv("OPENAI_API_KEY"));
    }

    public OpenAITTSProvider(String apiKey) {
        this.apiKey = apiKey;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
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
    public byte[] speak(String text, String voice, Map<String, Object> options) throws Exception {
        if (!isAvailable()) {
            throw new IllegalStateException("OPENAI_API_KEY not set");
        }

        Map<String, Object> body = Map.of(
                "model", "tts-1",
                "input", text,
                "voice", voice != null ? voice : "alloy"
        );

        Request request = new Request.Builder()
                .url(BASE_URL)
                .post(RequestBody.create(mapper.writeValueAsString(body), JSON))
                .header("Authorization", "Bearer " + apiKey)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new Exception("OpenAI TTS failed: " + response.code());
            }
            return response.body().bytes();
        }
    }

    @Override
    public List<Map<String, String>> listVoices() {
        List<Map<String, String>> voices = new ArrayList<>();
        voices.add(Map.of("id", "alloy", "name", "Alloy", "provider", "openai"));
        voices.add(Map.of("id", "echo", "name", "Echo", "provider", "openai"));
        voices.add(Map.of("id", "fable", "name", "Fable", "provider", "openai"));
        voices.add(Map.of("id", "onyx", "name", "Onyx", "provider", "openai"));
        voices.add(Map.of("id", "nova", "name", "Nova", "provider", "openai"));
        voices.add(Map.of("id", "shimmer", "name", "Shimmer", "provider", "openai"));
        return voices;
    }
}
