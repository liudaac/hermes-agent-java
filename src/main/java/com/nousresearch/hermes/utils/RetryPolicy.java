package com.nousresearch.hermes.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * 重试策略工具
 * 
 * 为持久化等关键操作提供可靠的重试机制。
 */
public class RetryPolicy {
    
    private static final Logger logger = LoggerFactory.getLogger(RetryPolicy.class);
    
    private final int maxRetries;
    private final Duration baseDelay;
    private final Duration maxDelay;
    private final double backoffMultiplier;
    
    /**
     * 默认策略：3次重试，基础延迟100ms，指数退避
     */
    public static RetryPolicy defaults() {
        return new RetryPolicy(3, Duration.ofMillis(100), Duration.ofSeconds(5), 2.0);
    }
    
    /**
     * 激进策略：5次重试，适合关键数据
     */
    public static RetryPolicy aggressive() {
        return new RetryPolicy(5, Duration.ofMillis(50), Duration.ofSeconds(10), 2.0);
    }
    
    /**
     * 保守策略：1次重试，适合非关键数据
     */
    public static RetryPolicy conservative() {
        return new RetryPolicy(1, Duration.ofMillis(200), Duration.ofSeconds(1), 1.5);
    }
    
    public RetryPolicy(int maxRetries, Duration baseDelay, Duration maxDelay, double backoffMultiplier) {
        this.maxRetries = maxRetries;
        this.baseDelay = baseDelay;
        this.maxDelay = maxDelay;
        this.backoffMultiplier = backoffMultiplier;
    }
    
    /**
     * 执行带重试的操作
     * @param operation 操作描述（用于日志）
     * @param supplier 要执行的操作
     * @return 操作结果
     */
    public <T> T execute(String operation, Supplier<T> supplier) {
        int attempt = 0;
        Duration delay = baseDelay;
        Exception lastException = null;
        
        while (attempt <= maxRetries) {
            try {
                T result = supplier.get();
                if (attempt > 0) {
                    logger.info("Operation '{}' succeeded after {} retries", operation, attempt);
                }
                return result;
            } catch (Exception e) {
                lastException = e;
                attempt++;
                
                if (attempt > maxRetries) {
                    logger.error("Operation '{}' failed after {} attempts: {}", 
                        operation, maxRetries + 1, e.getMessage());
                    break;
                }
                
                logger.warn("Operation '{}' failed (attempt {}/{}), retrying in {}ms: {}",
                    operation, attempt, maxRetries + 1, delay.toMillis(), e.getMessage());
                
                try {
                    Thread.sleep(delay.toMillis());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Retry interrupted", ie);
                }
                
                // 指数退避
                delay = Duration.ofMillis((long) (delay.toMillis() * backoffMultiplier));
                if (delay.compareTo(maxDelay) > 0) {
                    delay = maxDelay;
                }
            }
        }
        
        throw new RetryExhaustedException(
            String.format("Operation '%s' failed after %d attempts", operation, maxRetries + 1),
            lastException
        );
    }
    
    /**
     * 执行带重试的 void 操作
     */
    public void executeVoid(String operation, Runnable runnable) {
        execute(operation, () -> {
            runnable.run();
            return null;
        });
    }
    
    /**
     * 执行带重试的操作，允许自定义重试条件
     */
    public <T> T executeWithCondition(String operation, Supplier<T> supplier, java.util.function.Predicate<Exception> retryable) {
        int attempt = 0;
        Duration delay = baseDelay;
        Exception lastException = null;
        
        while (attempt <= maxRetries) {
            try {
                T result = supplier.get();
                if (attempt > 0) {
                    logger.info("Operation '{}' succeeded after {} retries", operation, attempt);
                }
                return result;
            } catch (Exception e) {
                lastException = e;
                
                // 检查是否应该重试
                if (!retryable.test(e)) {
                    logger.error("Operation '{}' failed with non-retryable error: {}", 
                        operation, e.getMessage());
                    throw new RetryExhaustedException(
                        String.format("Operation '%s' failed with non-retryable error", operation),
                        e
                    );
                }
                
                attempt++;
                
                if (attempt > maxRetries) {
                    logger.error("Operation '{}' failed after {} attempts: {}", 
                        operation, maxRetries + 1, e.getMessage());
                    break;
                }
                
                logger.warn("Operation '{}' failed (attempt {}/{}), retrying in {}ms: {}",
                    operation, attempt, maxRetries + 1, delay.toMillis(), e.getMessage());
                
                try {
                    Thread.sleep(delay.toMillis());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Retry interrupted", ie);
                }
                
                delay = Duration.ofMillis((long) (delay.toMillis() * backoffMultiplier));
                if (delay.compareTo(maxDelay) > 0) {
                    delay = maxDelay;
                }
            }
        }
        
        throw new RetryExhaustedException(
            String.format("Operation '%s' failed after %d attempts", operation, maxRetries + 1),
            lastException
        );
    }
    
    // Getters
    public int getMaxRetries() { return maxRetries; }
    public Duration getBaseDelay() { return baseDelay; }
    public Duration getMaxDelay() { return maxDelay; }
    public double getBackoffMultiplier() { return backoffMultiplier; }
    
    /**
     * 重试耗尽异常
     */
    public static class RetryExhaustedException extends RuntimeException {
        public RetryExhaustedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
