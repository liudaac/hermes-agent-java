package com.nousresearch.hermes.tenant.sandbox;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * 令牌桶速率限制器
 * 
 * 用于限制每秒请求数，支持突发流量和平滑限流
 */
public class RateLimiter {
    
    private final int maxRequestsPerSecond;
    private final AtomicLong tokens;
    private final AtomicLong lastRefillTime;
    private final long refillIntervalNanos;
    
    /**
     * 创建速率限制器
     * @param maxRequestsPerSecond 每秒最大请求数
     */
    public RateLimiter(int maxRequestsPerSecond) {
        this.maxRequestsPerSecond = maxRequestsPerSecond;
        this.tokens = new AtomicLong(maxRequestsPerSecond);
        this.lastRefillTime = new AtomicLong(System.nanoTime());
        this.refillIntervalNanos = 1_000_000_000L / maxRequestsPerSecond; // 每个令牌的间隔
    }
    
    /**
     * 尝试获取一个令牌（非阻塞）
     * @return true 如果获取成功，false 如果被限流
     */
    public boolean tryAcquire() {
        refillTokens();
        
        long currentTokens = tokens.get();
        if (currentTokens > 0) {
            return tokens.compareAndSet(currentTokens, currentTokens - 1);
        }
        return false;
    }
    
    /**
     * 获取一个令牌，如果被限流则等待
     * @throws InterruptedException 如果被中断
     */
    public void acquire() throws InterruptedException {
        while (!tryAcquire()) {
            // 计算等待时间
            long waitTime = calculateWaitTime();
            if (waitTime > 0) {
                TimeUnit.NANOSECONDS.sleep(waitTime);
            }
        }
    }
    
    /**
     * 尝试获取指定数量的令牌
     * @param permits 需要的令牌数
     * @return true 如果获取成功
     */
    public boolean tryAcquire(int permits) {
        if (permits <= 0) {
            return true;
        }
        
        refillTokens();
        
        long currentTokens = tokens.get();
        if (currentTokens >= permits) {
            return tokens.compareAndSet(currentTokens, currentTokens - permits);
        }
        return false;
    }
    
    /**
     * 补充令牌
     */
    private void refillTokens() {
        long now = System.nanoTime();
        long lastRefill = lastRefillTime.get();
        long elapsedNanos = now - lastRefill;
        
        if (elapsedNanos < refillIntervalNanos) {
            return; // 不需要补充
        }
        
        // 计算需要补充的令牌数
        long tokensToAdd = elapsedNanos / refillIntervalNanos;
        if (tokensToAdd <= 0) {
            return;
        }
        
        // 尝试更新令牌数
        long newTokens = Math.min(maxRequestsPerSecond, tokens.get() + tokensToAdd);
        
        if (lastRefillTime.compareAndSet(lastRefill, now)) {
            tokens.set(newTokens);
        }
    }
    
    /**
     * 计算获取下一个令牌需要等待的时间
     */
    private long calculateWaitTime() {
        refillTokens();
        if (tokens.get() > 0) {
            return 0;
        }
        return refillIntervalNanos;
    }
    
    /**
     * 获取当前可用令牌数
     */
    public long getAvailableTokens() {
        refillTokens();
        return tokens.get();
    }
    
    /**
     * 获取每秒最大请求数
     */
    public int getMaxRequestsPerSecond() {
        return maxRequestsPerSecond;
    }
}
