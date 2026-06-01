package com.nousresearch.hermes.tenant.metrics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TenantMetricsTest {

    @Test
    void exportsExtendedPrometheusMetrics() {
        TenantMetrics metrics = new TenantMetrics("tenant-a");
        metrics.setCurrentStorageUsage(12345L);

        String prometheus = metrics.exportPrometheusMetrics();

        assertTrue(prometheus.contains("hermes_tenant_memory_used_bytes"));
        assertTrue(prometheus.contains("hermes_tenant_network_requests_per_second"));
        assertTrue(prometheus.contains("hermes_tenant_storage_used_bytes{tenant=\"tenant-a\"} 12345"));
        assertTrue(prometheus.contains("hermes_tenant_file_count"));
        assertTrue(prometheus.contains("hermes_tenant_active_processes"));
        assertTrue(prometheus.contains("hermes_tenant_audit_events_last_hour"));
        assertTrue(prometheus.contains("hermes_tenant_quota_warning"));
        assertTrue(prometheus.contains("hermes_tenant_quota_exceeded"));
    }

    @Test
    void rpsIsZeroWithoutNetworkTraffic() {
        TenantMetrics metrics = new TenantMetrics("tenant-a");

        assertEquals(0, metrics.getNetworkRequestsPerSecond());
    }
}
