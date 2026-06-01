package com.nousresearch.hermes.tenant.metrics;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AlertManagerTest {

    @Test
    void tracksSuccessfulDeliveryAndCooldownSuppression() {
        MetricsCollector.AlertManager manager = new MetricsCollector.AlertManager();
        FakeChannel channel = new FakeChannel("fake-success", true);
        manager.registerChannel(channel);

        manager.fireAlert(MetricsCollector.AlertLevel.WARNING, "tenant-a", "MEMORY", "high");
        manager.fireAlert(MetricsCollector.AlertLevel.WARNING, "tenant-a", "MEMORY", "still high");

        assertEquals(1, channel.calls.get());
        String metrics = manager.exportPrometheusMetrics();
        assertTrue(metrics.contains("hermes_alerts_fired_total 1"));
        assertTrue(metrics.contains("hermes_alerts_suppressed_total 1"));
        assertTrue(metrics.contains("hermes_alert_deliveries_succeeded_total 1"));
        assertTrue(metrics.contains("hermes_alert_channel_deliveries_succeeded_total{channel=\"fake-success\"} 1"));
    }

    @Test
    void tracksFailedDelivery() {
        MetricsCollector.AlertManager manager = new MetricsCollector.AlertManager();
        FakeChannel channel = new FakeChannel("fake-failure", false);
        manager.registerChannel(channel);

        manager.fireAlert(MetricsCollector.AlertLevel.CRITICAL, "tenant-a", "STORAGE", "full");

        assertEquals(1, channel.calls.get());
        String metrics = manager.exportPrometheusMetrics();
        assertTrue(metrics.contains("hermes_alert_deliveries_failed_total"));
        assertTrue(metrics.contains("hermes_alert_channel_deliveries_failed_total{channel=\"fake-failure\"} 1"));
    }

    private static class FakeChannel implements AlertChannel {
        private final String name;
        private final boolean result;
        private final AtomicInteger calls = new AtomicInteger();

        private FakeChannel(String name, boolean result) {
            this.name = name;
            this.result = result;
        }

        @Override
        public boolean send(MetricsCollector.AlertLevel level, String tenantId, String type, String message) {
            calls.incrementAndGet();
            return result;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public boolean isAvailable() {
            return true;
        }
    }
}
