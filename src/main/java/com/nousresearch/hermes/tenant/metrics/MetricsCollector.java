package com.nousresearch.hermes.tenant.metrics;

import com.nousresearch.hermes.tenant.core.TenantContext;
import com.nousresearch.hermes.tenant.core.TenantManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.*;

/**
 * 全局指标采集器
 * 
 * 负责：
 * - 定期采集所有租户指标
 * - 聚合系统级指标
 * - 触发告警
 * - 导出到 Prometheus
 */
public class MetricsCollector {

    private static final Logger logger = LoggerFactory.getLogger(MetricsCollector.class);
    
    private final TenantManager tenantManager;
    private final ScheduledExecutorService scheduler;
    private final AlertManager alertManager;
    private final PrometheusExporter prometheusExporter;
    
    // 采集间隔
    private static final long COLLECT_INTERVAL_SECONDS = 30;
    
    public MetricsCollector(TenantManager tenantManager) {
        this.tenantManager = tenantManager;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "metrics-collector");
            t.setDaemon(true);
            return t;
        });
        this.alertManager = new AlertManager();
        this.prometheusExporter = new PrometheusExporter();
    }
    
    /**
     * 启动采集器
     */
    public void start() {
        logger.info("Starting metrics collector with interval: {}s", COLLECT_INTERVAL_SECONDS);
        
        scheduler.scheduleAtFixedRate(
            this::collectAll,
            COLLECT_INTERVAL_SECONDS,
            COLLECT_INTERVAL_SECONDS,
            TimeUnit.SECONDS
        );
    }
    
    /**
     * 停止采集器
     */
    public void stop() {
        logger.info("Stopping metrics collector");
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * 采集所有租户指标
     */
    private void collectAll() {
        try {
            Map<String, TenantContext> tenants = tenantManager.getAllTenants();
            
            for (TenantContext context : tenants.values()) {
                try {
                    collectTenantMetrics(context);
                } catch (Exception e) {
                    logger.error("Failed to collect metrics for tenant: {}", 
                        context.getTenantId(), e);
                }
            }
            
            // 导出到 Prometheus
            prometheusExporter.export(tenants);
            
        } catch (Exception e) {
            logger.error("Failed to collect metrics", e);
        }
    }
    
    /**
     * 采集单个租户指标
     */
    private void collectTenantMetrics(TenantContext context) {
        TenantMetrics metrics = context.getMetrics();
        if (metrics == null) {
            return;
        }
        
        // 检查告警条件
        checkAlerts(context, metrics);
    }
    
    /**
     * 检查告警条件
     */
    private void checkAlerts(TenantContext context, TenantMetrics metrics) {
        String tenantId = context.getTenantId();
        
        // 内存告警
        double memoryUsage = metrics.getMemoryUsagePercent();
        if (memoryUsage >= 0.95) {
            alertManager.fireAlert(AlertLevel.CRITICAL, tenantId, "MEMORY",
                String.format("Memory usage critical: %.1f%%", memoryUsage * 100));
        } else if (memoryUsage >= 0.8) {
            alertManager.fireAlert(AlertLevel.WARNING, tenantId, "MEMORY",
                String.format("Memory usage high: %.1f%%", memoryUsage * 100));
        }
        
        // 存储告警
        double storageUsage = metrics.getStorageUsagePercent();
        if (storageUsage >= 0.98) {
            alertManager.fireAlert(AlertLevel.CRITICAL, tenantId, "STORAGE",
                String.format("Storage usage critical: %.1f%%", storageUsage * 100));
        } else if (storageUsage >= 0.85) {
            alertManager.fireAlert(AlertLevel.WARNING, tenantId, "STORAGE",
                String.format("Storage usage high: %.1f%%", storageUsage * 100));
        }
        
        // 内存泄漏告警
        int leakCount = metrics.getMemoryLeakCount();
        if (leakCount > 10) {
            alertManager.fireAlert(AlertLevel.WARNING, tenantId, "MEMORY_LEAK",
                String.format("Potential memory leaks detected: %d", leakCount));
        }
        
        // 活跃 Agent 告警
        int activeAgents = metrics.getActiveAgents();
        if (activeAgents > 100) {
            alertManager.fireAlert(AlertLevel.WARNING, tenantId, "AGENTS",
                String.format("High number of active agents: %d", activeAgents));
        }
    }
    
    /**
     * 获取 Prometheus 格式的所有指标
     */
    public String exportPrometheusMetrics() {
        return prometheusExporter.exportAll(tenantManager.getAllTenants(), alertManager);
    }
    
    /**
     * 告警级别
     */
    public enum AlertLevel {
        INFO, WARNING, CRITICAL
    }
    
    /**
     * 告警管理器
     */
    public static class AlertManager {
        
        private final ConcurrentHashMap<String, Instant> lastAlertTime = new ConcurrentHashMap<>();
        private static final Duration ALERT_COOLDOWN = Duration.ofMinutes(5);
        private final java.util.List<AlertChannel> channels = new java.util.ArrayList<>();
        private final java.util.concurrent.atomic.AtomicLong alertsFired = new java.util.concurrent.atomic.AtomicLong(0);
        private final java.util.concurrent.atomic.AtomicLong alertsSuppressed = new java.util.concurrent.atomic.AtomicLong(0);
        private final java.util.concurrent.atomic.AtomicLong deliveriesSucceeded = new java.util.concurrent.atomic.AtomicLong(0);
        private final java.util.concurrent.atomic.AtomicLong deliveriesFailed = new java.util.concurrent.atomic.AtomicLong(0);
        private final ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong> channelSuccess = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong> channelFailure = new ConcurrentHashMap<>();
        
        public AlertManager() {
            // 注册默认渠道
            registerChannel(new EmailAlertChannel());
            registerChannel(new WebhookAlertChannel());
        }
        
        /**
         * 注册告警渠道
         */
        public void registerChannel(AlertChannel channel) {
            if (channel.isAvailable()) {
                channels.add(channel);
                channelSuccess.putIfAbsent(channel.getName(), new java.util.concurrent.atomic.AtomicLong(0));
                channelFailure.putIfAbsent(channel.getName(), new java.util.concurrent.atomic.AtomicLong(0));
                logger.info("Registered alert channel: {}", channel.getName());
            } else {
                logger.debug("Alert channel not available: {}", channel.getName());
            }
        }
        
        /**
         * 触发告警
         */
        public void fireAlert(AlertLevel level, String tenantId, String type, String message) {
            String alertKey = tenantId + ":" + type;
            Instant lastFired = lastAlertTime.get(alertKey);
            Instant now = Instant.now();
            
            // 冷却期检查
            if (lastFired != null && Duration.between(lastFired, now).compareTo(ALERT_COOLDOWN) < 0) {
                alertsSuppressed.incrementAndGet();
                return; // 冷却期内不重复告警
            }
            
            lastAlertTime.put(alertKey, now);
            alertsFired.incrementAndGet();
            
            // 记录告警
            String fullMessage = String.format("[%s] Tenant: %s, Type: %s, Message: %s",
                level, tenantId, type, message);
            
            switch (level) {
                case CRITICAL -> logger.error(fullMessage);
                case WARNING -> logger.warn(fullMessage);
                case INFO -> logger.info(fullMessage);
            }
            
            // 发送到外部系统
            sendExternalAlert(level, tenantId, type, message);
        }
        
        private void sendExternalAlert(AlertLevel level, String tenantId, String type, String message) {
            if (channels.isEmpty()) {
                deliveriesFailed.incrementAndGet();
                logger.debug("No alert channels configured");
                return;
            }
            
            int success = 0;
            for (AlertChannel channel : channels) {
                try {
                    boolean sent = channel.send(level, tenantId, type, message);
                    if (sent) {
                        success++;
                        deliveriesSucceeded.incrementAndGet();
                        channelSuccess.get(channel.getName()).incrementAndGet();
                    } else {
                        deliveriesFailed.incrementAndGet();
                        channelFailure.get(channel.getName()).incrementAndGet();
                    }
                } catch (Exception e) {
                    deliveriesFailed.incrementAndGet();
                    channelFailure.get(channel.getName()).incrementAndGet();
                    logger.error("Alert channel {} failed: {}", channel.getName(), e.getMessage());
                }
            }
            
            if (success == 0) {
                logger.warn("All alert channels failed for: {} - {}", type, tenantId);
            }
        }

        public String exportPrometheusMetrics() {
            StringBuilder sb = new StringBuilder();
            sb.append("# HELP hermes_alerts_fired_total Alerts fired after cooldown filtering\n");
            sb.append("# TYPE hermes_alerts_fired_total counter\n");
            sb.append("hermes_alerts_fired_total ").append(alertsFired.get()).append("\n");

            sb.append("# HELP hermes_alerts_suppressed_total Alerts suppressed by cooldown\n");
            sb.append("# TYPE hermes_alerts_suppressed_total counter\n");
            sb.append("hermes_alerts_suppressed_total ").append(alertsSuppressed.get()).append("\n");

            sb.append("# HELP hermes_alert_deliveries_succeeded_total Alert delivery successes\n");
            sb.append("# TYPE hermes_alert_deliveries_succeeded_total counter\n");
            sb.append("hermes_alert_deliveries_succeeded_total ").append(deliveriesSucceeded.get()).append("\n");

            sb.append("# HELP hermes_alert_deliveries_failed_total Alert delivery failures\n");
            sb.append("# TYPE hermes_alert_deliveries_failed_total counter\n");
            sb.append("hermes_alert_deliveries_failed_total ").append(deliveriesFailed.get()).append("\n");

            for (Map.Entry<String, java.util.concurrent.atomic.AtomicLong> entry : channelSuccess.entrySet()) {
                sb.append(String.format("hermes_alert_channel_deliveries_succeeded_total{channel=\"%s\"} %d\n",
                    entry.getKey(), entry.getValue().get()));
            }
            for (Map.Entry<String, java.util.concurrent.atomic.AtomicLong> entry : channelFailure.entrySet()) {
                sb.append(String.format("hermes_alert_channel_deliveries_failed_total{channel=\"%s\"} %d\n",
                    entry.getKey(), entry.getValue().get()));
            }
            return sb.toString();
        }
    }
    
    /**
     * Prometheus 指标导出器
     */
    public static class PrometheusExporter {
        
        /**
         * 导出所有租户指标
         */
        public String exportAll(Map<String, TenantContext> tenants, AlertManager alertManager) {
            StringBuilder sb = new StringBuilder();
            
            // 系统级指标
            sb.append("# HELP hermes_tenants_total Total number of tenants\n");
            sb.append("# TYPE hermes_tenants_total gauge\n");
            sb.append("hermes_tenants_total ").append(tenants.size()).append("\n");
            
            // 各租户指标
            for (TenantContext context : tenants.values()) {
                TenantMetrics metrics = context.getMetrics();
                if (metrics != null) {
                    sb.append(metrics.exportPrometheusMetrics());
                }
            }

            if (alertManager != null) {
                sb.append(alertManager.exportPrometheusMetrics());
            }
            
            return sb.toString();
        }
        
        /**
         * 导出到文件（供 Prometheus node_exporter 采集）
         */
        public void export(Map<String, TenantContext> tenants) {
            // 可以实现写入文本文件，供 node_exporter textfile collector 采集
        }
    }
}
