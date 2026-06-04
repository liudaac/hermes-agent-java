package com.nousresearch.hermes.tools.impl;

import com.nousresearch.hermes.config.ConfigManager;
import com.nousresearch.hermes.plugin.PluginManager;
import com.nousresearch.hermes.plugin.registry.ProviderRegistry;
import com.nousresearch.hermes.tools.ToolEntry;
import com.nousresearch.hermes.tools.ToolRegistry;
import com.nousresearch.hermes.tools.provider.TTSProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Text-to-Speech tool with pluggable backend support via ProviderRegistry.
 */
public class TTSTool {
    private static final Logger logger = LoggerFactory.getLogger(TTSTool.class);

    private ProviderRegistry<TTSProvider> providerRegistry;
    private TTSProvider primaryProvider;

    public TTSTool() {
        this(resolveRegistry());
    }

    public TTSTool(ProviderRegistry<TTSProvider> registry) {
        this.providerRegistry = registry;
        selectPrimaryProvider();
    }

    @SuppressWarnings("unchecked")
    private static ProviderRegistry<TTSProvider> resolveRegistry() {
        PluginManager pm = PluginManager.getInstance();
        if (pm != null) {
            return (ProviderRegistry<TTSProvider>) pm.getProviderRegistry("tts");
        }
        return null;
    }

    private void selectPrimaryProvider() {
        if (providerRegistry == null) {
            logger.warn("No provider registry configured for TTS");
            return;
        }
        String configured = ConfigManager.getInstance().getString("tts.provider");
        if (configured != null) {
            Optional<TTSProvider> match = providerRegistry.resolve(configured.toString());
            if (match.isPresent() && match.get().isAvailable()) {
                primaryProvider = match.get();
                logger.info("Using configured TTS provider: {}", primaryProvider.getName());
                return;
            }
        }
        for (TTSProvider provider : providerRegistry.listAll()) {
            if (provider.isAvailable()) {
                primaryProvider = provider;
                logger.info("Using available TTS provider: {}", provider.getName());
                return;
            }
        }
        logger.warn("No TTS provider available");
    }

    public void register(ToolRegistry registry) {
        registry.register(new ToolEntry.Builder()
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

        registry.register(new ToolEntry.Builder()
                .name("tts_voices")
                .toolset("tts")
                .schema(Map.of("description", "List available voices"))
                .handler(args -> listVoices()).emoji("🎙️").build());
    }

    private String speak(Map<String, Object> args) {
        String text = (String) args.get("text");
        String voice = (String) args.getOrDefault("voice", "alloy");
        String requestedProvider = (String) args.getOrDefault("provider", "openai");
        String outputPath = (String) args.get("output_path");

        try {
            Path output = outputPath != null ? Path.of(outputPath)
                    : Path.of(System.getProperty("java.io.tmpdir"), "tts_" + System.currentTimeMillis() + ".mp3");

            TTSProvider provider = selectProvider(requestedProvider);
            if (provider == null) {
                return ToolRegistry.toolError("No TTS provider available");
            }

            byte[] audio = provider.speak(text, voice, Map.of());
            Files.write(output, audio);

            return ToolRegistry.toolResult(Map.of(
                    "path", output.toString(),
                    "provider", provider.getName(),
                    "voice", voice,
                    "duration_seconds", estimateDuration(text)
            ));

        } catch (Exception e) {
            return ToolRegistry.toolError("TTS failed: " + e.getMessage());
        }
    }

    private String listVoices() {
        List<Map<String, String>> allVoices = new ArrayList<>();
        if (providerRegistry != null) {
            for (TTSProvider provider : providerRegistry.listAll()) {
                try {
                    allVoices.addAll(provider.listVoices());
                } catch (Exception e) {
                    logger.warn("Failed to list voices from {}: {}", provider.getName(), e.getMessage());
                }
            }
        }
        return ToolRegistry.toolResult(Map.of("voices", allVoices, "count", allVoices.size()));
    }

    private TTSProvider selectProvider(String requested) {
        if (requested != null && providerRegistry != null) {
            Optional<TTSProvider> match = providerRegistry.resolve(requested);
            if (match.isPresent() && match.get().isAvailable()) {
                return match.get();
            }
        }
        return primaryProvider;
    }

    private int estimateDuration(String text) {
        // Rough estimate: ~150 words per minute, ~5 chars per word
        return Math.max(1, text.length() / 25);
    }
}
