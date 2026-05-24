package com.nousresearch.hermes.gateway.platforms;

import com.alibaba.fastjson2.JSONObject;
import com.nousresearch.hermes.agent.AIAgent;
import com.nousresearch.hermes.gateway.IncomingMessage;

/**
 * Legacy lifecycle interface for platform adapters.
 *
 * <p>This interface now bridges to the tenant-aware gateway {@link
 * com.nousresearch.hermes.gateway.PlatformAdapter} contract so older platform
 * adapters are no longer a completely separate type hierarchy. Adapters can be
 * migrated incrementally by overriding {@link #parseWebhook(JSONObject)} when
 * they are ready to receive GatewayServerV2 webhooks.</p>
 */
public interface PlatformAdapter extends com.nousresearch.hermes.gateway.PlatformAdapter {
    
    /**
     * Get the platform name.
     */
    String getName();

    @Override
    default String getPlatformName() {
        return getName();
    }

    /**
     * Start the adapter.
     */
    void start() throws Exception;
    
    /**
     * Stop the adapter.
     */
    void stop() throws Exception;
    
    /**
     * Check if connected.
     */
    boolean isConnected();
    
    /**
     * Send a message to a chat.
     * @param chatId The chat/channel ID
     * @param message The message content
     */
    @Override
    void sendMessage(String chatId, String message) throws Exception;

    /**
     * Legacy adapters are often outbound/lifecycle-only. They can opt into
     * inbound webhooks by overriding this method.
     */
    @Override
    default IncomingMessage parseWebhook(JSONObject payload) {
        return null;
    }

    /**
     * Default reply behavior for platforms without a dedicated reply API.
     */
    @Override
    default void sendReply(String channel, String messageId, String content) throws Exception {
        sendMessage(channel, content);
    }
    
    /**
     * Set the legacy agent for handling messages. Tenant-aware gateway paths
     * should not call this; it remains for standalone lifecycle adapters.
     */
    void setAgent(AIAgent agent);
}
