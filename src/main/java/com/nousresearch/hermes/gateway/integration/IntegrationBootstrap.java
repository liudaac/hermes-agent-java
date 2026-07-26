package com.nousresearch.hermes.gateway.integration;

import com.nousresearch.hermes.config.repository.DataSourceFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;

/**
 * D1: Bootstrap for Integration Gateway components.
 *
 * <p>Lazy-initializes the BusinessSystemRegistry, AsyncTaskQueue, and
 * WebhookDispatcher from the shared DataSource.</p>
 *
 * <p>In LOCAL mode (no DB configured), these are null and /api/v1/
 * routes return 503 (service unavailable).</p>
 */
public class IntegrationBootstrap {

    private static final Logger logger = LoggerFactory.getLogger(IntegrationBootstrap.class);

    private static volatile BusinessSystemRegistry registry;
    private static volatile AsyncTaskQueue taskQueue;
    private static volatile WebhookDispatcher webhookDispatcher;
    private static volatile boolean initialized = false;

    public static synchronized void initialize() {
        if (initialized) return;
        try {
            DataSource ds = DataSourceFactory.create();
            registry = new BusinessSystemRegistry(ds);
            webhookDispatcher = new WebhookDispatcher(ds);

            // TaskQueue starts with a no-op processor; real processor is set by caller
            taskQueue = new AsyncTaskQueue(ds, task -> {
                throw new UnsupportedOperationException("Task processor not configured yet");
            });

            initialized = true;
            logger.info("IntegrationGateway initialized (BusinessSystemRegistry + AsyncTaskQueue + WebhookDispatcher)");
        } catch (Exception e) {
            logger.warn("IntegrationGateway not initialized (DB not configured?): {}", e.getMessage());
        }
    }

    public static BusinessSystemRegistry getRegistry() {
        if (!initialized) initialize();
        return registry;
    }

    public static AsyncTaskQueue getTaskQueue() {
        if (!initialized) initialize();
        return taskQueue;
    }

    public static WebhookDispatcher getWebhookDispatcher() {
        if (!initialized) initialize();
        return webhookDispatcher;
    }

    public static boolean isAvailable() {
        if (!initialized) initialize();
        return registry != null;
    }

    /**
     * Set a real task processor (called when TenantManager is available).
     */
    public static void setTaskProcessor(AsyncTaskQueue.TaskProcessor processor) {
        if (taskQueue != null && processor != null) {
            taskQueue.stop();
            DataSource ds = DataSourceFactory.create();
            taskQueue = new AsyncTaskQueue(ds, processor);
            taskQueue.start();
            logger.info("TaskQueue restarted with real processor");
        }
    }
}
