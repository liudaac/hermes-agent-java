package com.nousresearch.hermes.business.sla;

import java.time.Instant;

/**
 * SLA definition for business scenario execution.
 *
 * <p>Defines time thresholds and automatic actions for run monitoring.</p>
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

    // Preset factory methods
    public static SLADefinition customerService() {
        return new SLADefinition("Customer Service SLA", 240_000, 300_000, "cs-manager", "escalate");
    }

    public static SLADefinition orderProcessing() {
        return new SLADefinition("Order Processing SLA", 30_000, 60_000, "ops-manager", "auto_retry");
    }

    public static SLADefinition inventoryAlert() {
        return new SLADefinition("Inventory Alert SLA", 300_000, 600_000, "supply-manager", "escalate");
    }

    public static SLADefinition paymentProcessing() {
        return new SLADefinition("Payment Processing SLA", 10_000, 30_000, "finance-manager", "escalate");
    }

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
