package com.nousresearch.hermes.tools.provider;

import com.nousresearch.hermes.plugin.registry.NamedProvider;

import java.nio.file.Path;
import java.util.Map;

/**
 * Image generation provider interface.
 * Backends implement this to provide image generation capabilities.
 */
public interface ImageGenProvider extends NamedProvider {
    /**
     * Generate an image from a text prompt.
     *
     * @param prompt text description
     * @param size image size (e.g. "1024x1024")
     * @param quality quality level (e.g. "standard", "hd")
     * @param options provider-specific options
     * @return image bytes
     */
    byte[] generate(String prompt, String size, String quality, Map<String, Object> options) throws Exception;

    /**
     * Edit an existing image with a prompt.
     *
     * @param imagePath path to original image
     * @param prompt edit instruction
     * @param maskPath optional mask path
     * @param options provider-specific options
     * @return edited image bytes
     */
    byte[] edit(String imagePath, String prompt, String maskPath, Map<String, Object> options) throws Exception;

    /**
     * Check if this provider is available (keys configured).
     */
    boolean isAvailable();
}
