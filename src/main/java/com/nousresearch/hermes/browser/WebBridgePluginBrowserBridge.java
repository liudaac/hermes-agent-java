package com.nousresearch.hermes.browser;

/**
 * HTTP adapter for providers that implement Hermes' BrowserBridge HTTP contract.
 *
 * <p>This is intentionally separate from the official Kimi WebBridge daemon. Kimi's
 * official daemon is skill-backed and exposes {@code /status} plus {@code /command};
 * this adapter is for Hermes-compatible daemons that expose {@code /health},
 * {@code /capabilities}, and {@code /actions}.</p>
 */
public class WebBridgePluginBrowserBridge extends HttpBrowserBridge {
    public WebBridgePluginBrowserBridge(BrowserBridgeConfig config) {
        super("webbridge-contract", config);
    }
}
