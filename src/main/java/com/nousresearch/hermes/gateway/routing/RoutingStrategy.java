package com.nousresearch.hermes.gateway.routing;

/**
 * S2-1 #2: 路由策略接口。
 *
 * <p>决定一个请求应该由哪个节点处理。</p>
 */
public interface RoutingStrategy {

    /**
     * 解析目标节点。
     *
     * @param tenantId 租户 ID
     * @param sessionId 会话 ID（可选，用于更细粒度的 stickiness）
     * @return 目标节点
     */
    ClusterNode resolve(String tenantId, String sessionId);

    /**
     * 策略名称。
     */
    String name();
}
