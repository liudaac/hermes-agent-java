package com.nousresearch.hermes.gateway;

/**
 * Normalized inbound message from any gateway platform.
 *
 * This type is shared by GatewayServerV2 and platform adapters so the tenant-aware
 * gateway no longer depends on the legacy GatewayServer class for message shape.
 */
public record IncomingMessage(
    String id,
    String channel,
    String sender,
    String content,
    long timestamp,
    boolean isGroup
) {
    /**
     * Derive the user identifier for user-dimension isolation.
     * The sender field is populated by each platform adapter with the
     * platform-native user identifier (e.g. QQ user_openid, Feishu open_id).
     *
     * @return the sender, or null if unknown
     */
    public String userId() {
        return sender;
    }
}
