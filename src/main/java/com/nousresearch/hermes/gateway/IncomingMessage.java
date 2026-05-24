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
) {}
