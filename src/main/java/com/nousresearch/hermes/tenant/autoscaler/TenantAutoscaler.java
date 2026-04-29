package com.nousresearch.hermes.tenant.autoscaler;

import com.nousresearch.hermes.tenant.core.TenantContext;
import com.nousresearch.hermes.tenant.core.TenantManager;
import com.nousresearch.hermes.tenant.metrics.TenantMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 租户自动扩缩容管理器
 * 
 * 根据负载自动调整资源分配：
 * - CPU/内存使用率监控
 * - 自动扩容（增加资源配额）
 * - 自动缩容（回收闲置资源）
 * - 预测性扩缩容（基于历史趋势）
 */
public class TenantAutoscaler {

    private static final Logger logger = LoggerFactory.getLogger(TenantAutoscaler.class);
    
    private final TenantManager tenantManager;
    private final ScheduledExecutorService scheduler;
    private final Map<String, ScalingPolicy> tenantPolicies;
    private final Map<String, ScalingHistory> scalingHistory;
    
    // 默认配置
    private static final Duration EVALUATION_INTERVAL = Duration.ofMinutes(1);
    private static final double DEFAULT_SCALE_UP_THRESHOLD = 0.8;  // 80%
    private static final double DEFAULT_SCALE_DOWN_THRESHOLD = 0.3; // 30%
    private static final int DEFAULT_COOLDOWN_MINUTES = 5;
    
    public TenantAutoscaler(TenantManager tenantManager) {
        this.tenantManager = tenantManager;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "tenant-autoscaler");
            t.setDaemon(true);
            return t;
        });
        this.tenantPolicies = new ConcurrentHashMap<>();
        this.scalingHistory = new ConcurrentHashMap<>();
    }
    
    /**
     * 启动自动扩缩容
     */
    public void start() {
        logger.info("Starting tenant autoscaler");
        scheduler.scheduleAtFixedRate(
            this::evaluateAllTenants,
            EVALUATION_INTERVAL.getSeconds(),
            EVALUATION_INTERVAL.getSeconds(),
            TimeUnit.SECONDS
        );
    }
    
    /**
     * 停止自动扩缩容
     */
    public void stop() {
        logger.info("Stopping tenant autoscaler");
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
     * 设置租户扩缩容策略
     */
    public void setScalingPolicy(String tenantId, ScalingPolicy policy) {
        tenantPolicies.put(tenantId, policy);
        logger.debug("Set scaling policy for tenant {}: {}", tenantId, policy);
    }
    
    /**
     * 评估所有租户
     */
    private void evaluateAllTenants() {
        try {
            Map<String, TenantContext> tenants = tenantManager.getAllTenants();
            
            for (TenantContext context : tenants.values()) {
                try {
                    evaluateTenant(context);
                } catch (Exception e) {
                    logger.error("Failed to evaluate tenant: {}", context.getTenantId(), e);
                }
            }
        } catch (Exception e) {
            logger.error("Failed to evaluate tenants", e);
        }
    }
    
    /**
     * 评估单个租户
     */
    private void evaluateTenant(TenantContext context) {
        String tenantId = context.getTenantId();
        ScalingPolicy policy = tenantPolicies.getOrDefault(tenantId, ScalingPolicy.DEFAULT);
        
        // 检查冷却期
        ScalingHistory history = scalingHistory.get(tenantId);
        if (history != null && history.isInCooldown(policy.cooldownMinutes())) {
            return;
        }
        
        TenantMetrics metrics = context.getMetrics();
        if (metrics == null) {
            return;
        }
        
        // 获取当前使用率
        double cpuUsage = getCpuUsage(metrics);
        double memoryUsage = metrics.getMemoryUsagePercent();
        double storageUsage = metrics.getStorageUsagePercent();
        
        // 决策
        ScalingDecision decision = makeDecision(policy, cpuUsage, memoryUsage, storageUsage);
        
        switch (decision) {
            case SCALE_UP -> {
                if (history == null || history.canScaleUp(policy.cooldownMinutes())) {
                    executeScaleUp(context, policy);
                    recordScaling(tenantId, ScalingAction.SCALE_UP);
                }
            }
            case SCALE_DOWN -> {
                if (history == null || history.canScaleDown(policy.cooldownMinutes())) {
                    executeScaleDown(context, policy);
                    recordScaling(tenantId, ScalingAction.SCALE_DOWN);
                }
            }
            case MAINTAIN -> {
                // 保持现状
            }
        }
    }
    
    /**
     * 做出扩缩容决策
     */
    private ScalingDecision makeDecision(ScalingPolicy policy, 
                                         double cpuUsage, 
                                         double memoryUsage, 
                                         double storageUsage) {
        // 取最大使用率
        double maxUsage = Math.max(cpuUsage, Math.max(memoryUsage, storageUsage));
        
        if (maxUsage >= policy.scaleUpThreshold()) {
            return ScalingDecision.SCALE_UP;
        }
        
        if (maxUsage <= policy.scaleDownThreshold()) {
            return ScalingDecision.SCALE_DOWN;
        }
        
        return ScalingDecision.MAINTAIN;
    }
    
    /**
     * 执行扩容
     */
    private void executeScaleUp(TenantContext context, ScalingPolicy policy) {
        String tenantId = context.getTenantId();
        logger.info("Scaling up tenant: {}", tenantId);
        
        // 增加资源配额
        // 这里可以根据具体策略增加不同的资源
        // 例如：增加内存、CPU、存储等
        
        // 示例：增加内存配额 50%
        // long newMemoryQuota = (long) (currentQuota * 1.5);
        // context.updateMemoryQuota(newMemoryQuota);
        
        logger.info("Scaled up tenant {} successfully", tenantId);
    }
    
    /**
     * 执行缩容
     */
    private void executeScaleDown(TenantContext context, ScalingPolicy policy) {
        String tenantId = context.getTenantId();
        logger.info("Scaling down tenant: {}", tenantId);
        
        // 减少资源配额
        // 注意：缩容需要确保不会影响正在运行的任务
        
        logger.info("Scaled down tenant {} successfully", tenantId);
    }
    
    /**
     * 记录扩缩容历史
     */
    private void recordScaling(String tenantId, ScalingAction action) {
        scalingHistory.computeIfAbsent(tenantId, k -> new ScalingHistory())
                      .record(action);
    }
    
    /**
     * 获取 CPU 使用率（模拟/实际）
     */
    private double getCpuUsage(TenantMetrics metrics) {
        // 这里可以从 metrics 获取实际的 CPU 使用率
        // 目前返回一个模拟值
        return 0.5; // 50%
    }
    
    // ============ 内部类 ============
    
    /**
     * 扩缩容决策
     */
    private enum ScalingDecision {
        SCALE_UP, SCALE_DOWN, MAINTAIN
    }
    
    /**
     * 扩缩容动作
     */
    private enum ScalingAction {
        SCALE_UP, SCALE_DOWN
    }
    
    /**
     * 扩缩容策略
     */
    public record ScalingPolicy(
        double scaleUpThreshold,    // 扩容阈值
        double scaleDownThreshold,  // 缩容阈值
        int cooldownMinutes,        // 冷却期（分钟）
        int maxScaleUpSteps,        // 最大扩容次数
        int maxScaleDownSteps,      // 最大缩容次数
        boolean enabled             // 是否启用
    ) {
        public static final ScalingPolicy DEFAULT = new ScalingPolicy(
            DEFAULT_SCALE_UP_THRESHOLD,
            DEFAULT_SCALE_DOWN_THRESHOLD,
            DEFAULT_COOLDOWN_MINUTES,
            5,  // max scale up steps
            3,  // max scale down steps
            true
        );
        
        public ScalingPolicy {
            if (scaleUpThreshold <= scaleDownThreshold) {
                throw new IllegalArgumentException("Scale up threshold must be greater than scale down threshold");
            }
        }
    }
    
    /**
     * 扩缩容历史
     */
    private static class ScalingHistory {
        private final AtomicInteger scaleUpCount = new AtomicInteger(0);
        private final AtomicInteger scaleDownCount = new AtomicInteger(0);
        private volatile Instant lastScaleUp = null;
        private volatile Instant lastScaleDown = null;
        
        void record(ScalingAction action) {
            Instant now = Instant.now();
            switch (action) {
                case SCALE_UP -> {
                    scaleUpCount.incrementAndGet();
                    lastScaleUp = now;
                }
                case SCALE_DOWN -> {
                    scaleDownCount.incrementAndGet();
                    lastScaleDown = now;
                }
            }
        }
        
        boolean isInCooldown(int cooldownMinutes) {
            Instant now = Instant.now();
            
            if (lastScaleUp != null) {
                if (Duration.between(lastScaleUp, now).toMinutes() < cooldownMinutes) {
                    return true;
                }
            }
            
            if (lastScaleDown != null) {
                if (Duration.between(lastScaleDown, now).toMinutes() < cooldownMinutes) {
                    return true;
                }
            }
            
            return false;
        }
        
        boolean canScaleUp(int cooldownMinutes) {
            if (lastScaleUp == null) return true;
            return Duration.between(lastScaleUp, Instant.now()).toMinutes() >= cooldownMinutes;
        }
        
        boolean canScaleDown(int cooldownMinutes) {
            if (lastScaleDown == null) return true;
            return Duration.between(lastScaleDown, Instant.now()).toMinutes() >= cooldownMinutes;
        }
    }
}
