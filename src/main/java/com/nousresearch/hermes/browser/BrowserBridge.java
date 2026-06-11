package com.nousresearch.hermes.browser;

/**
 * Provider-neutral browser execution layer.
 *
 * <p>Adapters can back this with Playwright, OpenClaw Browser Relay, Kimi WebBridge,
 * or any local browser daemon. The interface is intentionally small so the rest of
 * Hermes can treat browser automation as a first-class execution environment rather
 * than binding to one provider.</p>
 */
public interface BrowserBridge {
    BrowserActionResult execute(BrowserAction action);

    default java.util.Map<String, Object> describe() {
        return java.util.Map.of(
            "provider", getClass().getSimpleName(),
            "class", getClass().getName(),
            "healthy", true
        );
    }

    default BrowserActionResult healthCheck() {
        return BrowserActionResult.ok(null, null, null, null, "Browser bridge is available", java.util.List.of());
    }
}
