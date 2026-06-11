package com.nousresearch.hermes.browser;

/**
 * Adapter skeleton for OpenClaw Browser Relay / local browser bridge daemon.
 */
public class OpenClawRelayBrowserBridge extends HttpBrowserBridge {
    public OpenClawRelayBrowserBridge(BrowserBridgeConfig config) {
        super("openclaw-relay", config);
    }
}
