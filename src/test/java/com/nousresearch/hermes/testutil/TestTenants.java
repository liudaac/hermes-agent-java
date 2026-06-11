package com.nousresearch.hermes.testutil;

import com.nousresearch.hermes.config.Constants;
import com.nousresearch.hermes.tenant.core.TenantContext;
import com.nousresearch.hermes.tenant.core.TenantProvisioningRequest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** Test helper for global TenantContext.create tests that otherwise leak tenant dirs. */
public final class TestTenants {
    private TestTenants() {}

    public static TenantContext create(String prefix) {
        return TenantContext.create(prefix + "-" + System.nanoTime(), new TenantProvisioningRequest());
    }

    public static void cleanup(TenantContext tenant) {
        if (tenant == null) return;
        try { tenant.destroy(false); } catch (Exception ignored) {}
        cleanup(tenant.getTenantId());
    }

    public static void cleanup(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) return;
        Path tenantDir = Constants.getHermesHome().resolve("tenants").resolve(tenantId);
        try {
            if (Files.exists(tenantDir)) {
                Files.walk(tenantDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try { Files.deleteIfExists(path); } catch (IOException ignored) {}
                    });
            }
        } catch (IOException ignored) {}
    }
}
