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
}
