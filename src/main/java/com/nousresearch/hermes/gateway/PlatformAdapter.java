package com.nousresearch.hermes.gateway;

import com.alibaba.fastjson2.JSONObject;

import java.util.Map;

/**
 * Platform adapter interface for integrating with messaging platforms.
 * Used by both GatewayServer and GatewayServerV2.
 */
public interface PlatformAdapter {
    String getPlatformName();

    /**
     * Return a platform-specific verification/challenge response, or null for normal webhooks.
     */
    default JSONObject getWebhookChallengeResponse(JSONObject payload) {
        return null;
    }

    /**
     * Verify webhook authenticity before parsing. Adapters without verification can use the default.
     */
    default boolean verifyWebhook(JSONObject payload, Map<String, String> headers, String rawBody) {
        return true;
    }

    IncomingMessage parseWebhook(JSONObject payload);
    void sendMessage(String channel, String content) throws Exception;
    void sendReply(String channel, String messageId, String content) throws Exception;
}
