package com.nousresearch.hermes.plugin.registry;

/**
 * Marker interface for providers that expose a canonical name.
 * Implemented by image_gen, browser, tts, transcription, web_search, video_gen providers.
 */
public interface NamedProvider {
    String getName();
}
