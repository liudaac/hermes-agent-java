package com.nousresearch.hermes.gateway;

import com.alibaba.fastjson2.JSONObject;

/**
 * Platform adapter interface for integrating with messaging platforms.
 * Used by both GatewayServer and GatewayServerV2.
 */
public interface PlatformAdapter {
    String getPlatformName();
    IncomingMessage parseWebhook(JSONObject payload);
    void sendMessage(String channel, String content) throws Exception;
    void sendReply(String channel, String messageId, String content) throws Exception;
}
