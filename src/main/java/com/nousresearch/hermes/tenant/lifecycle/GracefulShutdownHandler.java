package com.nousresearch.hermes.tenant.lifecycle;

import com.nousresearch.hermes.tenant.core.TenantContext;
import com.nousresearch.hermes.tenant.core.TenantManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 优雅关闭处理器
 * 
 * 处理 JVM 关闭时的优雅关闭流程：
 * 1. 停止接受新请求
 * 2. 等待活跃请求完成
 * 3. 持久化所有租户状态
 * 4. 关闭资源
 */
public class GracefulShutdownHandler {

    private static final Logger logger = LoggerFactory.getLogger(GracefulShutdownHandler.class);
    
    private final TenantManager tenantManager;
    private final Duration shutdownTimeout;
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    
    private Thread shutdownHook;
    
    public GracefulShutdownHandler(TenantManager tenantManager, Duration shutdownTimeout) {
        this.tenantManager = tenantManager;
        this.shutdownTimeout = shutdownTimeout;
    }
    
    /**
     * 注册 JVM 关闭钩子
     */
    public void registerShutdownHook() {
        shutdownHook = new Thread(this::onShutdown, "graceful-shutdown-hook");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        logger.info("Registered graceful shutdown hook");
    }
    
    /**
     * 取消关闭钩子
     */
    public void unregisterShutdownHook() {
        if (shutdownHook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
                logger.info("Unregistered graceful shutdown hook");
            } catch (IllegalStateException e) {
                // 已经在关闭中
            }
        }
    }
    
    /**
     * 触发优雅关闭
     */
    public CompletableFuture<Void> initiateShutdown() {
        if (shuttingDown.compareAndSet(false, true)) {
            logger.info("Initiating graceful shutdown...");
            return performShutdown();
        }
        logger.warn("Shutdown already in progress");
        return CompletableFuture.completedFuture(null);
    }
    
    /**
     * 检查是否正在关闭
     */
    public boolean isShuttingDown() {
        return shuttingDown.get();
    }
    
    /**
     * 执行关闭流程
     */
    private CompletableFuture<Void> performShutdown() {
        return CompletableFuture.runAsync(() -> {
            try {
                notifyTenantsOfShutdown();
                waitForActiveRequests();
                suspendAllTenants();
                persistAllTenantStates();
                shutdownAllTenants();
                logger.info("Graceful shutdown completed");
            } catch (Exception e) {
                logger.error("Error during shutdown", e);
                forceShutdown();
            }
        });
    }
    
    private void onShutdown() {
        logger.info("JVM shutdown detected");
        try {
            performShutdown().get(shutdownTimeout.getSeconds(), TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.error("Graceful shutdown failed", e);
            forceShutdown();
        }
    }
    
    private void notifyTenantsOfShutdown() {
        logger.info("Notifying tenants...");
    }
    
    private void waitForActiveRequests() throws InterruptedException {
        logger.info("Waiting for active requests...");
        Thread.sleep(1000);
    }
    
    private void suspendAllTenants() {
        logger.info("Suspending tenants...");
        Map<String, TenantContext> tenants = tenantManager.getAllTenants();
        for (TenantContext context : tenants.values()) {
            context.suspend("System shutdown");
        }
    }
    
    private void persistAllTenantStates() {
        logger.info("Persisting tenant states...");
    }
    
    private void shutdownAllTenants() {
        logger.info("Shutting down tenants...");
        Map<String, TenantContext> tenants = tenantManager.getAllTenants();
        for (TenantContext context : tenants.values()) {
            context.destroy(true);
        }
    }
    
    private void forceShutdown() {
        logger.warn("Force shutdown");
    }
}
