package com.nousresearch.hermes.browser;

/**
 * HTTP adapter for the WebBridge plugin daemon.
 *
 * <p>The plugin is expected to expose the Hermes BrowserBridge HTTP contract:
 * {@code GET /health}, {@code GET /capabilities}, and {@code POST /actions} by default.
 * Alternative route layouts can be supplied through {@link BrowserBridgeConfig} and
 * discovered via {@code BrowserBridgeProviderProbe}.</p>
 */
public class WebBridgePluginBrowserBridge extends HttpBrowserBridge {
    public WebBridgePluginBrowserBridge(BrowserBridgeConfig config) {
        super("webbridge-plugin", config);
    }
}
