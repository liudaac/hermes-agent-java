package com.nousresearch.hermes.tools.impl;

import com.nousresearch.hermes.config.ConfigManager;
import com.nousresearch.hermes.plugin.PluginManager;
import com.nousresearch.hermes.plugin.registry.ProviderRegistry;
import com.nousresearch.hermes.tools.ToolEntry;
import com.nousresearch.hermes.tools.ToolRegistry;
import com.nousresearch.hermes.tools.provider.ImageGenProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Image generation tool with pluggable backend support via ProviderRegistry.
 */
public class ImageGenerationTool {
    private static final Logger logger = LoggerFactory.getLogger(ImageGenerationTool.class);

    private ProviderRegistry<ImageGenProvider> providerRegistry;
    private ImageGenProvider primaryProvider;

    public ImageGenerationTool() {
        this(resolveRegistry());
    }

    public ImageGenerationTool(ProviderRegistry<ImageGenProvider> registry) {
        this.providerRegistry = registry;
        selectPrimaryProvider();
    }

    @SuppressWarnings("unchecked")
    private static ProviderRegistry<ImageGenProvider> resolveRegistry() {
        PluginManager pm = PluginManager.getInstance();
        if (pm != null) {
            return (ProviderRegistry<ImageGenProvider>) pm.getProviderRegistry("image_gen");
        }
        return null;
    }

    private void selectPrimaryProvider() {
        if (providerRegistry == null) {
            logger.warn("No provider registry configured for image generation");
            return;
        }
        String configured = ConfigManager.getInstance().getString("image_gen.provider");
        if (configured != null) {
            Optional<ImageGenProvider> match = providerRegistry.resolve(configured.toString());
            if (match.isPresent() && match.get().isAvailable()) {
                primaryProvider = match.get();
                logger.info("Using configured image provider: {}", primaryProvider.getName());
                return;
            }
        }
        for (ImageGenProvider provider : providerRegistry.listAll()) {
            if (provider.isAvailable()) {
                primaryProvider = provider;
                logger.info("Using available image provider: {}", provider.getName());
                return;
            }
        }
        logger.warn("No image generation provider available");
    }

    public void register(ToolRegistry registry) {
        registry.register(new ToolEntry.Builder()
                .name("image_generate")
                .toolset("image")
                .schema(Map.of("description", "Generate image from text prompt",
                        "parameters", Map.of("type", "object",
                                "properties", Map.of(
                                        "prompt", Map.of("type", "string"),
                                        "size", Map.of("type", "string", "enum", List.of("1024x1024", "1792x1024", "1024x1792"), "default", "1024x1024"),
                                        "quality", Map.of("type", "string", "enum", List.of("standard", "hd"), "default", "standard"),
                                        "provider", Map.of("type", "string", "enum", List.of("openai", "stability"), "default", "openai"),
                                        "output_path", Map.of("type", "string")),
                                "required", List.of("prompt"))))
                .handler(this::generateImage).emoji("🎨").build());

        registry.register(new ToolEntry.Builder()
                .name("image_edit")
                .toolset("image")
                .schema(Map.of("description", "Edit an image with a prompt",
                        "parameters", Map.of("type", "object",
                                "properties", Map.of(
                                        "image_path", Map.of("type", "string"),
                                        "prompt", Map.of("type", "string"),
                                        "mask_path", Map.of("type", "string")),
                                "required", List.of("image_path", "prompt"))))
                .handler(this::editImage).emoji("✏️").build());
    }

    private String generateImage(Map<String, Object> args) {
        String prompt = (String) args.get("prompt");
        String size = (String) args.getOrDefault("size", "1024x1024");
        String quality = (String) args.getOrDefault("quality", "standard");
        String requestedProvider = (String) args.getOrDefault("provider", "openai");
        String outputPath = (String) args.get("output_path");

        try {
            Path output = outputPath != null ? Path.of(outputPath)
                    : Path.of(System.getProperty("java.io.tmpdir"), "image_" + System.currentTimeMillis() + ".png");

            ImageGenProvider provider = selectProvider(requestedProvider);
            if (provider == null) {
                return ToolRegistry.toolError("No image generation provider available");
            }

            byte[] image = provider.generate(prompt, size, quality, Map.of());
            Files.write(output, image);

            return ToolRegistry.toolResult(Map.of(
                    "path", output.toString(),
                    "provider", provider.getName(),
                    "prompt", prompt,
                    "size", size
            ));

        } catch (Exception e) {
            return ToolRegistry.toolError("Generation failed: " + e.getMessage());
        }
    }

    private String editImage(Map<String, Object> args) {
        String imagePath = (String) args.get("image_path");
        String prompt = (String) args.get("prompt");
        String maskPath = (String) args.get("mask_path");

        try {
            ImageGenProvider provider = primaryProvider != null ? primaryProvider : selectProvider(null);
            if (provider == null) {
                return ToolRegistry.toolError("No image generation provider available");
            }

            byte[] image = provider.edit(imagePath, prompt, maskPath, Map.of());
            Path output = Path.of(System.getProperty("java.io.tmpdir"), "image_edit_" + System.currentTimeMillis() + ".png");
            Files.write(output, image);

            return ToolRegistry.toolResult(Map.of(
                    "path", output.toString(),
                    "provider", provider.getName()
            ));

        } catch (Exception e) {
            return ToolRegistry.toolError("Edit failed: " + e.getMessage());
        }
    }

    private ImageGenProvider selectProvider(String requested) {
        if (requested != null && providerRegistry != null) {
            Optional<ImageGenProvider> match = providerRegistry.resolve(requested);
            if (match.isPresent() && match.get().isAvailable()) {
                return match.get();
            }
        }
        return primaryProvider;
    }
}
