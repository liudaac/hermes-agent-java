package com.nousresearch.hermes.browser;

/**
 * Runtime configuration for BrowserBridge provider selection.
 */
public record BrowserBridgeConfig(
    String provider,
    String endpoint,
    int timeoutMs,
    String actionPath,
    String healthPath,
    String capabilitiesPath
) {
    public BrowserBridgeConfig(String provider, String endpoint, int timeoutMs) {
        this(provider, endpoint, timeoutMs, "/actions", "/health", "/capabilities");
    }

    public static BrowserBridgeConfig fromEnvironment() {
        String provider = firstNonBlank(
            System.getProperty("hermes.browser.bridge.provider"),
            System.getenv("HERMES_BROWSER_BRIDGE_PROVIDER"),
            "mock"
        ).trim().toLowerCase();

        String endpoint = firstNonBlank(
            System.getProperty("hermes.browser.bridge.endpoint"),
            System.getenv("HERMES_BROWSER_BRIDGE_ENDPOINT"),
            defaultEndpoint(provider)
        );

        int timeout = parseInt(firstNonBlank(
            System.getProperty("hermes.browser.bridge.timeoutMs"),
            System.getenv("HERMES_BROWSER_BRIDGE_TIMEOUT_MS"),
            "10000"
        ), 10000);

        String actionPath = firstNonBlank(
            System.getProperty("hermes.browser.bridge.actionPath"),
            System.getenv("HERMES_BROWSER_BRIDGE_ACTION_PATH"),
            "/actions"
        );
        String healthPath = firstNonBlank(
            System.getProperty("hermes.browser.bridge.healthPath"),
            System.getenv("HERMES_BROWSER_BRIDGE_HEALTH_PATH"),
            "/health"
        );
        String capabilitiesPath = firstNonBlank(
            System.getProperty("hermes.browser.bridge.capabilitiesPath"),
            System.getenv("HERMES_BROWSER_BRIDGE_CAPABILITIES_PATH"),
            "/capabilities"
        );

        return new BrowserBridgeConfig(provider, endpoint, Math.max(1000, timeout), normalizePath(actionPath), normalizePath(healthPath), normalizePath(capabilitiesPath));
    }

    private static String defaultEndpoint(String provider) {
        return switch (provider) {
            case "kimi", "kimi-webbridge", "kimi_webbridge" -> "http://127.0.0.1:17361";
            case "openclaw", "openclaw-relay", "openclaw_relay" -> "http://127.0.0.1:14511";
            default -> "";
        };
    }

    static String normalizePath(String value) {
        if (value == null || value.isBlank()) return "/";
        String trimmed = value.trim();
        return trimmed.startsWith("/") ? trimmed : "/" + trimmed;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return "";
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }
}
