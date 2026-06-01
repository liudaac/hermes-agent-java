package com.nousresearch.hermes.tenant.metrics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 告警通知渠道接口
 */
public interface AlertChannel {
    
    /**
     * 发送告警
     * @param level 告警级别
     * @param tenantId 租户ID
     * @param type 告警类型
     * @param message 告警消息
     * @return 是否发送成功
     */
    boolean send(MetricsCollector.AlertLevel level, String tenantId, String type, String message);
    
    /**
     * 渠道名称
     */
    String getName();
    
    /**
     * 是否可用
     */
    boolean isAvailable();
}
