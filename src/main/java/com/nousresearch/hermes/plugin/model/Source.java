package com.nousresearch.hermes.plugin.model;

/**
 * Source of a plugin — determines override precedence.
 * Later sources override earlier ones on key collision.
 */
public enum Source {
    /**
     * Shipped with hermes-agent distribution.
     */
    BUNDLED("bundled"),

    /**
     * Installed by user in ~/.hermes/plugins/.
     */
    USER("user"),

    /**
     * Project-local in ./.hermes/plugins/.
     */
    PROJECT("project"),

    /**
     * Pip/entry-point installed package.
     */
    ENTRYPOINT("entrypoint");

    private final String value;

    Source(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static Source fromString(String raw) {
        if (raw == null) return BUNDLED;
        String s = raw.strip().toLowerCase();
        for (Source src : values()) {
            if (src.value.equals(s)) return src;
        }
        return BUNDLED;
    }
}
