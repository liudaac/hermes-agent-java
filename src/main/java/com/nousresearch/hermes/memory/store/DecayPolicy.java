package com.nousresearch.hermes.memory.store;

import java.time.Duration;
import java.util.Objects;

/**
 * Tenant-configurable decay policy for short-term session memory.
 *
 * <p>Defines the time windows and weights for each decay stage:</p>
 * <pre>
 *   FULL  (original, 100%)  &rarr;  WARM  (original, 70%)  &rarr;  COOL  (summary, 30%)  &rarr;  EVICT
 *   |&lt;-- fullWindow --&gt;|       |&lt;-- warmWindow --&gt;|       |&lt;-- coolWindow --&gt;|
 * </pre>
 *
 * <h2>Preset policies</h2>
 * <ul>
 *   <li>{@link #aggressive()} &mdash; hours-level, for high-frequency short tasks</li>
 *   <li>{@link #standard()}   &mdash; day-level (default)</li>
 *   <li>{@link #longRunning()} &mdash; week-level, for ops/monitoring agents</li>
 *   <li>{@link #archival()}   &mdash; month-level, for compliance/audit</li>
 * </ul>
 *
 * <p>Policies are stored per-tenant in {@code TenantConfig} and can be
 * hot-updated without restart.</p>
 */
public class DecayPolicy {

    private final Duration fullWindow;
    private final Duration warmWindow;
    private final Duration coolWindow;
    private final double fullWeight;
    private final double warmWeight;
    private final double coolWeight;
    private final Duration decayCycleInterval;
    private final int summaryBatchSize;
    private final boolean extractFactsOnEvict;
    private final int maxFactsPerEviction;

    private DecayPolicy(Builder b) {
        this.fullWindow = Objects.requireNonNull(b.fullWindow);
        this.warmWindow = Objects.requireNonNull(b.warmWindow);
        this.coolWindow = Objects.requireNonNull(b.coolWindow);
        this.fullWeight = b.fullWeight;
        this.warmWeight = b.warmWeight;
        this.coolWeight = b.coolWeight;
        this.decayCycleInterval = b.decayCycleInterval;
        this.summaryBatchSize = b.summaryBatchSize;
        this.extractFactsOnEvict = b.extractFactsOnEvict;
        this.maxFactsPerEviction = b.maxFactsPerEviction;
    }

    // ── Presets ─────────────────────────────────────────────

    /** Fast decay: hours-level. For customer service / one-shot task agents. */
    public static DecayPolicy aggressive() {
        return builder()
            .fullWindow(Duration.ofHours(2))
            .warmWindow(Duration.ofHours(8))
            .coolWindow(Duration.ofHours(24))
            .warmWeight(0.6)
            .coolWeight(0.2)
            .decayCycleInterval(Duration.ofMinutes(10))
            .summaryBatchSize(5)
            .build();
    }

    /** Default: day-level decay. For general-purpose business agents. */
    public static DecayPolicy standard() {
        return builder()
            .fullWindow(Duration.ofDays(1))
            .warmWindow(Duration.ofDays(3))
            .coolWindow(Duration.ofDays(7))
            .warmWeight(0.7)
            .coolWeight(0.3)
            .decayCycleInterval(Duration.ofMinutes(30))
            .summaryBatchSize(10)
            .build();
    }

    /** Slow decay: week-level. For ops / monitoring / long-running agents. */
    public static DecayPolicy longRunning() {
        return builder()
            .fullWindow(Duration.ofDays(3))
            .warmWindow(Duration.ofDays(7))
            .coolWindow(Duration.ofDays(30))
            .warmWeight(0.8)
            .coolWeight(0.5)
            .decayCycleInterval(Duration.ofHours(1))
            .summaryBatchSize(20)
            .build();
    }

    /** Very slow decay: month-level. For compliance / audit / archival. */
    public static DecayPolicy archival() {
        return builder()
            .fullWindow(Duration.ofDays(7))
            .warmWindow(Duration.ofDays(30))
            .coolWindow(Duration.ofDays(90))
            .warmWeight(0.9)
            .coolWeight(0.7)
            .decayCycleInterval(Duration.ofHours(6))
            .summaryBatchSize(50)
            .extractFactsOnEvict(true)
            .maxFactsPerEviction(10)
            .build();
    }

    // ── Builder ─────────────────────────────────────────────

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Duration fullWindow = Duration.ofDays(1);
        private Duration warmWindow = Duration.ofDays(3);
        private Duration coolWindow = Duration.ofDays(7);
        private double fullWeight = 1.0;
        private double warmWeight = 0.7;
        private double coolWeight = 0.3;
        private Duration decayCycleInterval = Duration.ofMinutes(30);
        private int summaryBatchSize = 10;
        private boolean extractFactsOnEvict = true;
        private int maxFactsPerEviction = 5;

        public Builder fullWindow(Duration v) { this.fullWindow = v; return this; }
        public Builder warmWindow(Duration v) { this.warmWindow = v; return this; }
        public Builder coolWindow(Duration v) { this.coolWindow = v; return this; }
        public Builder fullWeight(double v) { this.fullWeight = v; return this; }
        public Builder warmWeight(double v) { this.warmWeight = v; return this; }
        public Builder coolWeight(double v) { this.coolWeight = v; return this; }
        public Builder decayCycleInterval(Duration v) { this.decayCycleInterval = v; return this; }
        public Builder summaryBatchSize(int v) { this.summaryBatchSize = v; return this; }
        public Builder extractFactsOnEvict(boolean v) { this.extractFactsOnEvict = v; return this; }
        public Builder maxFactsPerEviction(int v) { this.maxFactsPerEviction = v; return this; }

        public DecayPolicy build() {
            if (!warmWindow.isNegative() && !coolWindow.isNegative()
                && warmWindow.compareTo(coolWindow) > 0) {
                throw new IllegalArgumentException(
                    "warmWindow must be <= coolWindow");
            }
            if (fullWindow.compareTo(warmWindow) > 0) {
                throw new IllegalArgumentException(
                    "fullWindow must be <= warmWindow");
            }
            return new DecayPolicy(this);
        }
    }

    // ── Getters ─────────────────────────────────────────────

    public Duration getFullWindow() { return fullWindow; }
    public Duration getWarmWindow() { return warmWindow; }
    public Duration getCoolWindow() { return coolWindow; }
    public double getFullWeight() { return fullWeight; }
    public double getWarmWeight() { return warmWeight; }
    public double getCoolWeight() { return coolWeight; }
    public Duration getDecayCycleInterval() { return decayCycleInterval; }
    public int getSummaryBatchSize() { return summaryBatchSize; }
    public boolean isExtractFactsOnEvict() { return extractFactsOnEvict; }
    public int getMaxFactsPerEviction() { return maxFactsPerEviction; }

    /**
     * Classify a message age into a decay stage.
     *
     * @param ageMs age in milliseconds since the message was created
     * @return the decay stage
     */
    public RecallStage classifyStage(long ageMs) {
        long fullMs = fullWindow.toMillis();
        long warmMs = warmWindow.toMillis();
        long coolMs = coolWindow.toMillis();
        if (ageMs < fullMs) return RecallStage.FULL;
        if (ageMs < warmMs) return RecallStage.WARM;
        if (ageMs < coolMs) return RecallStage.COOL;
        return RecallStage.EVICT;
    }

    public double stageWeight(RecallStage stage) {
        return switch (stage) {
            case FULL -> fullWeight;
            case WARM -> warmWeight;
            case COOL -> coolWeight;
            case EVICT -> 0.0;
        };
    }
}
