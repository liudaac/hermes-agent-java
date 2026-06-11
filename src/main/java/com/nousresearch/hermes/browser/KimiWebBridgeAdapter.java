package com.nousresearch.hermes.browser;

/**
 * Adapter skeleton for Kimi WebBridge's local daemon.
 *
 * <p>It uses the BrowserBridge canonical HTTP contract until the exact daemon
 * route is specialized. Configure with:</p>
 * <ul>
 *   <li>-Dhermes.browser.bridge.provider=kimi</li>
 *   <li>-Dhermes.browser.bridge.endpoint=http://127.0.0.1:PORT</li>
 * </ul>
 */
public class KimiWebBridgeAdapter extends HttpBrowserBridge {
    public KimiWebBridgeAdapter(BrowserBridgeConfig config) {
        super("kimi-webbridge", config);
    }
}
