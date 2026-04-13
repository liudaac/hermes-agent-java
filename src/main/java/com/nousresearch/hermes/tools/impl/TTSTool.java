package com.nousresearch.hermes.tools.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nousresearch.hermes.tools.ToolRegistry;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Text-to-Speech tool.
 * Supports: OpenAI TTS, ElevenLabs.
 */
public class TTSTool {
    private static final Logger logger = LoggerFactory.getLogger(TTSTool.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    
    private final OkHttpClient httpClient;
    private final String openaiKey;
    private final String elevenlabsKey;
    
    public TTSTool() {
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();
        
        this.openaiKey = System.getenv("OPENAI_API_KEY");
        this.elevenlabsKey = System.getenv("ELEVENLABS_API_KEY");
    }
    
    public void register(ToolRegistry registry) {
        registry.register(new ToolRegistry.Builder()
            .name("tts_speak")
            .toolset("tts")
            .schema(Map.of("description", "Convert text to speech",
                "parameters", Map.of("type", "object",
                    "properties", Map.of(
                        "text", Map.of("type", "string"),
                        "voice", Map.of("type", "string", "default", "alloy"),
                        "provider", Map.of("type", "string", "enum", List.of("openai", "elevenlabs"), "default", "openai"),
                        "output_path", Map.of("type", "string")),
                    "required", List.of("text"))))
            .handler(this::speak).emoji("🔊").build());
        
        registry.register(new ToolRegistry.Builder()
            .name("tts_voices")
            .toolset("tts")
            .schema(Map.of("description", "List available voices"))
            .handler(args -> listVoices()).emoji("🎙️").build());
    }
    
    private String speak(Map<String, Object> args) {
        String text = (String) args.get("text");
        String voice = (String) args.getOrDefault("voice", "alloy");
        String provider = (String) args.getOrDefault("provider", "openai");
        String outputPath = (String) args.get("output_path");
        
        try {
            Path output = outputPath != null ? Path.of(outputPath) 
                : Path.of(System.getProperty("java.io.tmpdir"), "tts_" + System.currentTimeMillis() + ".mp3");
            
            byte[] audio = switch (provider) {
                case "elevenlabs" -> speakWithElevenLabs(text, voice);
                default -> speakWithOpenAI(text, voice);
            };
            
            Files.write(output, audio);
            
            return ToolRegistry.toolResult(Map.of(
                "path", output.toString(),
                "provider", provider,
                "voice", voice,
                "duration_seconds", estimateDuration(text)
            ));
            
        } catch (Exception e) {
            return ToolRegistry.toolError("TTS failed: " + e.getMessage());
        }
    }
    
    private byte[] speakWithOpenAI(String text, String voice) throws Exception {
        if (openaiKey == null) {
            throw new IllegalStateException("OPENAI_API_KEY not set");
        }
        
        Map<String, Object> body = Map.of(
            "model", "tts-1",
            "input", text,
            "voice", voice
        );
        
        Request request = new Request.Builder()
            .url("https://api.openai.com/v1/audio/speech")
            .post(RequestBody.create(mapper.writeValueAsString(body), JSON))
            .header("Authorization", "Bearer " + openaiKey)
            .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new Exception("OpenAI TTS failed: " + response.code());
            }
            return response.body().bytes();
        }
    }
    
    private byte[] speakWithElevenLabs(String text, String voice) throws Exception {
        if (elevenlabsKey == null) {
            throw new IllegalStateException("ELEVENLABS_API_KEY not set");
        }
        
        String voiceId = voice.equals("alloy") ? "21m00Tcm4TlvDq8ikWAM" : voice;
        
        Map<String, Object> body = Map.of(
            "text", text,
            "model_id", "eleven_multilingual_v2"
        );
        
        Request request = new Request.Builder()
            .url("https://api.elevenlabs.io/v1/text-to-speech/" + voiceId)
            .post(RequestBody.create(mapper.writeValueAsString(body), JSON))
            .header("xi-api-key", elevenlabsKey)
            .build();
        
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new Exception("ElevenLabs failed: " + response.code());
            }
            return response.body().bytes();
        }
    }
    
    private String listVoices() {
        List<Map<String, String>> voices = new ArrayList<>();
        
        voices.add(Map.of("id", "alloy", "name", "Alloy", "provider", "openai"));
        voices.add(Map.of("id", "echo", "name", "Echo", "provider", "openai"));
        voices.add(Map.of("id", "fable", "name", "Fable", "provider", "openai"));
        voices.add(Map.of("id", "onyx", "name", "Onyx", "provider", "openai"));
        voices.add(Map.of("id", "nova", "name", "Nova", "provider", "openai"));
        voices.add(Map.of("id", "shimmer", "name", "Shimmer", "provider", "openai"));
        
        return ToolRegistry.toolResult(Map.of("voices", voices));
    }
    
    private int estimateDuration(String text) {
        // Rough estimate: ~150 words per minute
        int words = text.split("\\s+").length;
        return Math.max(1, words / 150 * 60);
    }
}
