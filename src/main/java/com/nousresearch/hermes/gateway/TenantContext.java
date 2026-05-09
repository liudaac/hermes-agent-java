package com.nousresearch.hermes.gateway;

/**
 * Thread-local context for tenant isolation.
 * Tracks the current tenant ID for the executing thread.
 */
public class TenantContext implements AutoCloseable {
    private static final ThreadLocal<String> currentTenant = new ThreadLocal<>();
    private final String previousTenant;

    private TenantContext(String tenantId) {
        this.previousTenant = currentTenant.get();
        currentTenant.set(tenantId);
    }

    /**
     * Set the current tenant for this thread.
     * Returns a TenantContext that should be used in a try-with-resources block.
     */
    public static TenantContext withTenant(String tenantId) {
        return new TenantContext(tenantId);
    }

    /**
     * Get the current tenant ID for this thread.
     */
    public static String getCurrentTenant() {
        return currentTenant.get();
    }

    /**
     * Clear the tenant context for this thread.
     */
    public static void clear() {
        currentTenant.remove();
    }

    @Override
    public void close() {
        if (previousTenant == null) {
            currentTenant.remove();
        } else {
            currentTenant.set(previousTenant);
        }
    }
}
