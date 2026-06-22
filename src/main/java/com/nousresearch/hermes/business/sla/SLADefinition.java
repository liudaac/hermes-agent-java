package com.nousresearch.hermes.business.sla;

import java.time.Instant;

/**
 * SLA（服务级别协议）定义 — 描述业务场景执行的时间阈值和违约动作。
 *
 * <p>核心字段：
 * <ul>
 *   <li>warnThresholdMs: 预警阈值（毫秒），到达时发送告警但不中断执行</li>
 *   <li>breachThresholdMs: 违约阈值（毫秒），到达时触发 actionOnBreach 指定的动作</li>
 *   <li>escalationTarget: 升级目标（如 "ops-manager"），用于 escalate 动作时指定审批接收人</li>
 *   <li>actionOnBreach: 违约动作 — "auto_retry"（自动重试）/ "escalate"（升级审批）/ "cancel"（取消）</li>
 * </ul>
 * <p>预设工厂方法覆盖电商物流最常见的 5 类 SLA。</p>
 */
public class SLADefinition {
    private String name;
    private long warnThresholdMs;
    private long breachThresholdMs;
    private String escalationTarget;
    private String actionOnBreach;
    private Instant createdAt;

    public SLADefinition() {
        this.createdAt = Instant.now();
    }

    public SLADefinition(String name, long warnThresholdMs, long breachThresholdMs,
                         String escalationTarget, String actionOnBreach) {
        this.name = name;
        this.warnThresholdMs = warnThresholdMs;
        this.breachThresholdMs = breachThresholdMs;
        this.escalationTarget = escalationTarget;
        this.actionOnBreach = actionOnBreach;
        this.createdAt = Instant.now();
    }

    // ---- 预设工厂方法：覆盖电商物流核心场景 ----

    /** 客服 SLA：5 分钟预警 / 10 分钟违约 — 客服响应时效敏感 */
    public static SLADefinition customerService() {
        return new SLADefinition("Customer Service SLA", 240_000, 300_000, "cs-manager", "escalate");
    }

    /** 订单处理 SLA：30 秒预警 / 60 秒违约 — 下单流程要求极速响应 */
    public static SLADefinition orderProcessing() {
        return new SLADefinition("Order Processing SLA", 30_000, 60_000, "ops-manager", "auto_retry");
    }

    /** 库存预警 SLA：5 分钟预警 / 10 分钟违约 — 库存监控允许一定延迟 */
    public static SLADefinition inventoryAlert() {
        return new SLADefinition("Inventory Alert SLA", 300_000, 600_000, "supply-manager", "escalate");
    }

    /** 支付处理 SLA：10 秒预警 / 30 秒违约 — 支付网关超时容忍极低 */
    public static SLADefinition paymentProcessing() {
        return new SLADefinition("Payment Processing SLA", 10_000, 30_000, "finance-manager", "escalate");
    }

    /** 通用 SLA：5 分钟预警 / 10 分钟违约 — 未指定场景时的默认兜底 */
    public static SLADefinition general() {
        return new SLADefinition("General SLA", 300_000, 600_000, "business-admin", "escalate");
    }

    // Getters / setters
    public String getName() { return name; }
    public SLADefinition setName(String name) { this.name = name; return this; }
    public long getWarnThresholdMs() { return warnThresholdMs; }
    public SLADefinition setWarnThresholdMs(long warnThresholdMs) { this.warnThresholdMs = warnThresholdMs; return this; }
    public long getBreachThresholdMs() { return breachThresholdMs; }
    public SLADefinition setBreachThresholdMs(long breachThresholdMs) { this.breachThresholdMs = breachThresholdMs; return this; }
    public String getEscalationTarget() { return escalationTarget; }
    public SLADefinition setEscalationTarget(String escalationTarget) { this.escalationTarget = escalationTarget; return this; }
    public String getActionOnBreach() { return actionOnBreach; }
    public SLADefinition setActionOnBreach(String actionOnBreach) { this.actionOnBreach = actionOnBreach; return this; }
    public Instant getCreatedAt() { return createdAt; }
    public SLADefinition setCreatedAt(Instant createdAt) { this.createdAt = createdAt; return this; }
}
