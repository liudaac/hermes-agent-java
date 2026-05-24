package com.nousresearch.hermes.dashboard;

import java.util.List;

/**
 * Runtime view of the webhook/API gateway for dashboard status responses.
 */
public record GatewayRuntimeStatus(
    boolean running,
    Integer port,
    String state,
    String healthUrl,
    String exitReason,
    Long updatedAt,
    List<String> platforms
) {
    public static GatewayRuntimeStatus disconnected() {
        return new GatewayRuntimeStatus(
            false,
            null,
            null,
            null,
            null,
            null,
            List.of()
        );
    }
}
