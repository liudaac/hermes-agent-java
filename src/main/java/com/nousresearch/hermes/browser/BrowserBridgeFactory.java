package com.nousresearch.hermes.browser;

/**
 * Selects a concrete BrowserBridge implementation from runtime config.
 */
public final class BrowserBridgeFactory {
    private BrowserBridgeFactory() {}

    public static BrowserBridge create() {
        return create(BrowserBridgeConfig.fromEnvironment());
    }

    public static BrowserBridge create(BrowserBridgeConfig config) {
        String provider = config != null && config.provider() != null ? config.provider().trim().toLowerCase() : "mock";
        return switch (provider) {
            case "mock", "test", "memory", "" -> new MockBrowserBridge();
            case "webbridge", "webbridge-plugin", "webbridge_plugin", "web-bridge", "plugin" -> new WebBridgePluginBrowserBridge(config);
            case "kimi", "kimi-webbridge", "kimi_webbridge" -> new KimiWebBridgeAdapter(config);
            case "openclaw", "openclaw-relay", "openclaw_relay", "relay" -> new OpenClawRelayBrowserBridge(config);
            default -> new UnavailableBrowserBridge(provider, "Unknown browser bridge provider: " + provider);
        };
    }

    private record UnavailableBrowserBridge(String provider, String reason) implements BrowserBridge {
        @Override
        public BrowserActionResult execute(BrowserAction action) {
            return BrowserActionResult.error(action != null ? action.sessionId() : null,
                "provider_unknown", reason + ". Supported providers: mock, webbridge, kimi, openclaw");
        }

        @Override
        public java.util.Map<String, Object> describe() {
            return java.util.Map.of("provider", provider, "healthy", false, "error_code", "provider_unknown", "message", reason);
        }

        @Override
        public java.util.Map<String, Object> capabilities() {
            return java.util.Map.of("ok", false, "provider", provider, "error_code", "provider_unknown", "message", reason);
        }
    }
}
