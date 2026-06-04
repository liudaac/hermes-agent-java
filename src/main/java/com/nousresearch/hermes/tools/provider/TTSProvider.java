package com.nousresearch.hermes.tools.provider;

import com.nousresearch.hermes.plugin.registry.NamedProvider;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Text-to-Speech provider interface.
 */
public interface TTSProvider extends NamedProvider {
    /**
     * Convert text to speech audio.
     *
     * @param text text to speak
     * @param voice voice identifier
     * @param options provider-specific options
     * @return audio bytes
     */
    byte[] speak(String text, String voice, Map<String, Object> options) throws Exception;

    /**
     * List available voices.
     *
     * @return list of voice metadata maps
     */
    List<Map<String, String>> listVoices();

    /**
     * Check if this provider is available.
     */
    boolean isAvailable();
}
