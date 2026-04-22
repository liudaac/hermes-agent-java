package com.nousresearch.hermes.tools.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Chrome DevTools Protocol (CDP) tool.
 * Mirrors Python tools/browser_cdp_tool.py
 *
 * Sends arbitrary CDP commands to the browser's DevTools WebSocket endpoint.
 */
public class BrowserCdpTool {
    private static final Logger logger = LoggerFactory.getLogger(BrowserCdpTool.class);

    private static final String CDP_DOCS_URL = "https://chromedevtools.github.io/devtools-protocol/";
    private static final int DEFAULT_TIMEOUT_MS = 30000;

    private String cdpEndpoint;

    public BrowserCdpTool() {
        this.cdpEndpoint = resolveCdpEndpoint();
    }

    /**
     * Resolve CDP endpoint from environment or config.
     */
    private String resolveCdpEndpoint() {
        // 1. Check environment variable
        String envUrl = System.getenv("BROWSER_CDP_URL");
        if (envUrl != null && !envUrl.isEmpty()) {
            return envUrl;
        }

        // 2. Check system property
        String propUrl = System.getProperty("browser.cdp.url");
        if (propUrl != null && !propUrl.isEmpty()) {
            return propUrl;
        }

        // 3. Default localhost
        return "ws://localhost:9222/devtools/browser";
    }

    /**
     * Send a CDP command.
     *
     * @param method CDP method name (e.g., "Runtime.evaluate")
     * @param params Method parameters as JSON object
     * @return Response from CDP
     */
    public JSONObject sendCommand(String method, JSONObject params) throws Exception {
        if (cdpEndpoint == null || cdpEndpoint.isEmpty()) {
            throw new IllegalStateException("CDP endpoint not configured");
        }

        int id = (int) (System.currentTimeMillis() % 100000);

        JSONObject command = new JSONObject();
        command.put("id", id);
        command.put("method", method);
        if (params != null) {
            command.put("params", params);
        }

        CompletableFuture<JSONObject> future = new CompletableFuture<>();

        WebSocketClient client = new WebSocketClient(new URI(cdpEndpoint)) {
            @Override
            public void onOpen(ServerHandshake handshake) {
                logger.debug("CDP WebSocket connected");
                send(command.toJSONString());
            }

            @Override
            public void onMessage(String message) {
                try {
                    JSONObject response = JSON.parseObject(message);
                    Integer responseId = response.getInteger("id");
                    if (responseId != null && responseId == id) {
                        future.complete(response);
                        close();
                    }
                } catch (Exception e) {
                    logger.error("Error parsing CDP response", e);
                }
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                if (!future.isDone()) {
                    future.completeExceptionally(new RuntimeException("WebSocket closed: " + reason));
                }
            }

            @Override
            public void onError(Exception ex) {
                future.completeExceptionally(ex);
            }
        };

        client.connect();

        try {
            return future.get(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } finally {
            client.close();
        }
    }

    /**
     * Evaluate JavaScript in the browser.
     *
     * @param expression JavaScript expression
     * @return Evaluation result
     */
    public JSONObject evaluate(String expression) throws Exception {
        JSONObject params = new JSONObject();
        params.put("expression", expression);
        params.put("returnByValue", true);

        return sendCommand("Runtime.evaluate", params);
    }

    /**
     * Navigate to a URL.
     *
     * @param url URL to navigate to
     * @return Navigation result
     */
    public JSONObject navigate(String url) throws Exception {
        JSONObject params = new JSONObject();
        params.put("url", url);

        return sendCommand("Page.navigate", params);
    }

    /**
     * Get document cookie.
     *
     * @return Cookie string
     */
    public String getCookies() throws Exception {
        JSONObject response = sendCommand("Network.getAllCookies", null);
        JSONObject result = response.getJSONObject("result");
        if (result != null) {
            return result.getString("cookies");
        }
        return null;
    }

    /**
     * Set CDP endpoint.
     *
     * @param endpoint WebSocket endpoint URL
     */
    public void setCdpEndpoint(String endpoint) {
        this.cdpEndpoint = endpoint;
    }

    /**
     * Check if CDP is available.
     *
     * @return true if endpoint is configured
     */
    public boolean isAvailable() {
        return cdpEndpoint != null && !cdpEndpoint.isEmpty();
    }
}
