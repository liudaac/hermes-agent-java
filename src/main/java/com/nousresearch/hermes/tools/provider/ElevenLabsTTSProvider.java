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
 * ElevenLabs TTS provider.
 */
public class ElevenLabsTTSProvider implements TTSProvider {
    private static final Logger logger = LoggerFactory.getLogger(ElevenLabsTTSProvider.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final String BASE_URL = "https://api.elevenlabs.io/v1/text-to-speech";

    private final OkHttpClient httpClient;
    private final String apiKey;

    public ElevenLabsTTSProvider() {
        this(System.getenv("ELEVENLABS_API_KEY"));
    }

    public ElevenLabsTTSProvider(String apiKey) {
        this.apiKey = apiKey;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public String getName() {
        return "elevenlabs";
    }

    @Override
    public boolean isAvailable() {
        return apiKey != null && !apiKey.isEmpty();
    }

    @Override
    public byte[] speak(String text, String voice, Map<String, Object> options) throws Exception {
        if (!isAvailable()) {
            throw new IllegalStateException("ELEVENLABS_API_KEY not set");
        }

        String voiceId = voice != null && !voice.equals("alloy") ? voice : "21m00Tcm4TlvDq8ikWAM";

        Map<String, Object> body = Map.of(
                "text", text,
                "model_id", "eleven_multilingual_v2"
        );

        Request request = new Request.Builder()
                .url(BASE_URL + "/" + voiceId)
                .post(RequestBody.create(mapper.writeValueAsString(body), JSON))
                .header("xi-api-key", apiKey)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new Exception("ElevenLabs TTS failed: " + response.code());
            }
            return response.body().bytes();
        }
    }

    @Override
    public List<Map<String, String>> listVoices() {
        List<Map<String, String>> voices = new ArrayList<>();
        voices.add(Map.of("id", "21m00Tcm4TlvDq8ikWAM", "name", "Rachel", "provider", "elevenlabs"));
        voices.add(Map.of("id", "AZnzlk1XvdvUeBnXmlld", "name", "Domi", "provider", "elevenlabs"));
        voices.add(Map.of("id", "EXAVITQu4vr4xnSDxMaL", "name", "Bella", "provider", "elevenlabs"));
        voices.add(Map.of("id", "ErXwobaYiN019PkySvjV", "name", "Antoni", "provider", "elevenlabs"));
        voices.add(Map.of("id", "MF3mGyEYCl7XYWbV9V6O", "name", "Elli", "provider", "elevenlabs"));
        voices.add(Map.of("id", "TxGEqnHWrfWFTfGW9XjX", "name", "Josh", "provider", "elevenlabs"));
        return voices;
    }
}
