# 线程池隔离详细实现方案

## 1. TenantThreadPool 核心实现

```java
package com.nousresearch.hermes.tenant.concurrent;

import com.nousresearch.hermes.tenant.core.TenantContext;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 租户级线程池 - 隔离线程资源
 */
public class TenantThreadPool {
    
    private final String tenantId;
    private final ThreadPoolExecutor executor;
    private final int maxThreads;
    private final AtomicLong submittedTasks = new AtomicLong(0);
    private final AtomicLong completedTasks = new AtomicLong(0);
    private final AtomicLong rejectedTasks = new AtomicLong(0);
    
    public TenantThreadPool(String tenantId, TenantPoolConfig config) {
        this.tenantId = tenantId;
        this.maxThreads = config.getMaxThreads();
        
        // 创建有界队列
        BlockingQueue<Runnable> queue = new LinkedBlockingQueue<>(config.getQueueCapacity());
        
        // 创建线程工厂（带租户标识）
        ThreadFactory threadFactory = new TenantThreadFactory(tenantId);
        
        // 创建拒绝策略
        RejectedExecutionHandler rejectionHandler = (r, e) -> {
            rejectedTasks.incrementAndGet();
            throw new TenantPoolRejectedException(
                "Task rejected for tenant " + tenantId + ": pool full"
            );
        };
        
        // 创建线程池
        this.executor = new ThreadPoolExecutor(
            config.getCoreThreads(),
            config.getMaxThreads(),
            config.getKeepAliveSeconds(),
            TimeUnit.SECONDS,
            queue,
            threadFactory,
            rejectionHandler
        );
        
        // 允许核心线程超时
        executor.allowCoreThreadTimeOut(true);
    }
    
    /**
     * 提交任务
     */
    public <T> Future<T> submit(Callable<T> task) {
        submittedTasks.incrementAndGet();
        
        return executor.submit(() -> {
            try {
                T result = task.call();
                completedTasks.incrementAndGet();
                return result;
            } catch (Exception e) {
                throw e;
            }
        });
    }
    
    /**
     * 提交任务（Runnable）
     */
    public Future<?> submit(Runnable task) {
        submittedTasks.incrementAndGet();
        
        return executor.submit(() -> {
            try {
                task.run();
                completedTasks.incrementAndGet();
            } catch (Exception e) {
                throw e;
            }
        });
    }
    
    /**
     * 获取活跃线程数
     */
    public int getActiveThreads() {
        return executor.getActiveCount();
    }
    
    /**
     * 获取队列大小
     */
    public int getQueueSize() {
        return executor.getQueue().size();
    }
    
    /**
     * 获取最大线程数
     */
    public int getMaxThreads() {
        return maxThreads;
    }
    
    /**
     * 获取统计信息
     */
    public PoolStatistics getStatistics() {
        return new PoolStatistics(
            submittedTasks.get(),
            completedTasks.get(),
            rejectedTasks.get(),
            getActiveThreads(),
            executor.getPoolSize(),
            getQueueSize()
        );
    }
    
    /**
     * 关闭线程池
     */
    public void shutdown() {
        executor.shutdown();
    }
    
    /**
     * 立即关闭
     */
    public void shutdownNow() {
        executor.shutdownNow();
    }
    
    /**
     * 租户线程工厂
     */
    private static class TenantThreadFactory implements ThreadFactory {
        private final String tenantId;
        private final AtomicInteger counter = new AtomicInteger(0);
        
        TenantThreadFactory(String tenantId) {
            this.tenantId = tenantId;
        }
        
        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(r);
            thread.setName("hermes-" + tenantId + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
    
    /**
     * 线程池统计
     */
    public record PoolStatistics(
        long submittedTasks,
        long completedTasks,
        long rejectedTasks,
        int activeThreads,
        int poolSize,
        int queueSize
    ) {}
}

/**
 * 租户线程池配置
 */
public class TenantPoolConfig {
    private int coreThreads = 2;
    private int maxThreads = 10;
    private int queueCapacity = 100;
    private long keepAliveSeconds = 60;
    
    // Builder
    public static Builder builder() {
        return new Builder();
    }
    
    public static class Builder {
        private TenantPoolConfig config = new TenantPoolConfig();
        
        public Builder coreThreads(int n) {
            config.coreThreads = n;
            return this;
        }
        
        public Builder maxThreads(int n) {
            config.maxThreads = n;
            return this;
        }
        
        public Builder queueCapacity(int n) {
            config.queueCapacity = n;
            return this;
        }
        
        public Builder keepAliveSeconds(long seconds) {
            config.keepAliveSeconds = seconds;
            return this;
        }
        
        public TenantPoolConfig build() {
            return config;
        }
    }
    
    // Getters...
    public int getCoreThreads() { return coreThreads; }
    public int getMaxThreads() { return maxThreads; }
    public int getQueueCapacity() { return queueCapacity; }
    public long getKeepAliveSeconds() { return keepAliveSeconds; }
}

/**
 * 线程池拒绝异常
 */
public class TenantPoolRejectedException extends RejectedExecutionException {
    public TenantPoolRejectedException(String message) {
        super(message);
    }
}
```

## 2. 集成到 TenantContext

```java
public class TenantContext {
    private final TenantThreadPool threadPool;
    
    public TenantContext(TenantProvisioningRequest request, ...) {
        // ... 现有代码 ...
        
        // 创建租户线程池
        TenantPoolConfig poolConfig = request.getPoolConfig() != null 
            ? request.getPoolConfig()
            : TenantPoolConfig.builder()
                .coreThreads(2)
                .maxThreads(10)
                .queueCapacity(100)
                .build();
        
        this.threadPool = new TenantThreadPool(request.getTenantId(), poolConfig);
    }
    
    /**
     * 在租户线程池中执行异步任务
     */
    public <T> Future<T> submitAsync(Callable<T> task) {
        return threadPool.submit(task);
    }
    
    /**
     * 获取线程池统计
     */
    public TenantThreadPool.PoolStatistics getThreadPoolStats() {
        return threadPool.getStatistics();
    }
    
    @Override
    public void close() {
        // ... 现有清理代码 ...
        threadPool.shutdown();
    }
}
```

## 3. 全局线程池管理器

```java
package com.nousresearch.hermes.tenant.concurrent;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 全局线程池管理器 - 管理所有租户的线程池
 */
public class GlobalThreadPoolManager {
    
    private final ConcurrentHashMap<String, TenantThreadPool> pools = new ConcurrentHashMap<>();
    private final int globalMaxThreads;
    private final AtomicInteger globalThreadCount = new AtomicInteger(0);
    
    public GlobalThreadPoolManager(int globalMaxThreads) {
        this.globalMaxThreads = globalMaxThreads;
    }
    
    /**
     * 注册租户线程池
     */
    public void registerPool(String tenantId, TenantThreadPool pool) {
        // 检查全局限制
        if (globalThreadCount.addAndGet(pool.getMaxThreads()) > globalMaxThreads) {
            globalThreadCount.addAndGet(-pool.getMaxThreads());
            throw new IllegalStateException("Global thread limit exceeded");
        }
        
        pools.put(tenantId, pool);
    }
    
    /**
     * 注销租户线程池
     */
    public void unregisterPool(String tenantId) {
        TenantThreadPool pool = pools.remove(tenantId);
        if (pool != null) {
            pool.shutdown();
            globalThreadCount.addAndGet(-pool.getMaxThreads());
        }
    }
    
    /**
     * 获取全局统计
     */
    public GlobalStatistics getGlobalStatistics() {
        long totalActive = pools.values().stream()
            .mapToInt(TenantThreadPool::getActiveThreads)
            .sum();
        
        return new GlobalStatistics(
            pools.size(),
            globalThreadCount.get(),
            globalMaxThreads,
            totalActive
        );
    }
    
    public record GlobalStatistics(
        int tenantCount,
        int totalThreads,
        int maxThreads,
        long activeThreads
    ) {}
}
```

## 4. 使用示例

```java
// 在租户上下文中执行异步任务
TenantContext context = tenantManager.getTenant(tenantId);

// 提交异步任务
Future<String> result = context.submitAsync(() -> {
    // 执行耗时操作
    return processLargeFile();
});

// 获取线程池统计
TenantThreadPool.PoolStatistics stats = context.getThreadPoolStats();
System.out.println("Active threads: " + stats.activeThreads());
System.out.println("Queue size: " + stats.queueSize());
System.out.println("Rejected tasks: " + stats.rejectedTasks());

// 全局统计
GlobalThreadPoolManager globalManager = ...;
GlobalThreadPoolManager.GlobalStatistics global = globalManager.getGlobalStatistics();
System.out.println("Total threads across all tenants: " + global.totalThreads());
```
