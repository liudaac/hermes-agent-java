package com.nousresearch.hermes.plugin.model;

/**
 * Plugin kind determines loading behavior and discovery path.
 * Mirrors Python _VALID_PLUGIN_KINDS.
 */
public enum PluginKind {
    /**
     * Default. Has its own hooks/tools. Opt-in via plugins.enabled.
     */
    STANDALONE("standalone"),

    /**
     * Pluggable backend for an existing core tool (e.g. image_gen, browser).
     * Bundled backends auto-load; user-installed still gated by plugins.enabled.
     */
    BACKEND("backend"),

    /**
     * Category with exactly one active provider (e.g. memory).
     * Handled by category's own discovery system; general scanner skips these.
     */
    EXCLUSIVE("exclusive"),

    /**
     * Gateway messaging platform adapter (e.g. Feishu, QQBot).
     * Bundled platform plugins auto-load so every shipped platform works OOTB.
     */
    PLATFORM("platform"),

    /**
     * LLM model provider (e.g. OpenAI, DeepSeek).
     * Handled by providers discovery system; general scanner skips these.
     */
    MODEL_PROVIDER("model-provider");

    private final String value;

    PluginKind(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static PluginKind fromString(String raw) {
        if (raw == null) return STANDALONE;
        String s = raw.strip().toLowerCase();
        for (PluginKind k : values()) {
            if (k.value.equals(s)) return k;
        }
        return STANDALONE;
    }
}
