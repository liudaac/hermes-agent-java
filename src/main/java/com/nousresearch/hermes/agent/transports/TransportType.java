package com.nousresearch.hermes.agent.transports;

/**
 * Enum representing different transport types for model communication.
 * Mirrors Python hermes.agent.transports.types.TransportType
 */
public enum TransportType {
    ANTHROPIC("anthropic"),
    BEDROCK("bedrock"),
    CHAT_COMPLETIONS("chat_completions"),
    CODEX("codex");

    private final String value;

    TransportType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static TransportType fromString(String value) {
        for (TransportType type : values()) {
            if (type.value.equalsIgnoreCase(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown transport type: " + value);
    }
}
