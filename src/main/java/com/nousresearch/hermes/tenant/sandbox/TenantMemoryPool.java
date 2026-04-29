package com.nousresearch.hermes.tenant.sandbox;

import java.lang.ref.Cleaner;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 租户级内存池 - JVM 内存隔离
 * 
 * 提供真正的内存隔离：
 * - 使用堆外内存（Off-Heap），绕过 JVM GC
 * - 精确追踪每字节分配
 * - 硬性配额限制
 * - 自动检测内存泄漏
 */
public class TenantMemoryPool {

    private static final Cleaner cleaner = Cleaner.create();
    private static final double WARNING_THRESHOLD = 0.8;  // 80% 告警阈值

    private final String tenantId;
    private final long maxMemoryBytes;
    private final AtomicLong usedMemory = new AtomicLong(0);
    private final Map<String, AllocationInfo> allocations = new ConcurrentHashMap<>();
    private final AtomicReference<MemoryStats> statsCache = new AtomicReference<>();
    private volatile long lastStatsUpdate = 0;

    public TenantMemoryPool(String tenantId, long maxMemoryBytes) {
        this.tenantId = tenantId;
        this.maxMemoryBytes = maxMemoryBytes;
    }

    /**
     * 分配内存
     * 
     * @param size 分配大小（字节）
     * @return 分配的 ByteBuffer
     * @throws MemoryQuotaExceededException 如果超出配额
     */
    public ByteBuffer allocate(int size) throws MemoryQuotaExceededException {
        if (size <= 0) {
            throw new IllegalArgumentException("Size must be positive: " + size);
        }

        // 检查配额
        long currentUsed = usedMemory.get();
        if (currentUsed + size > maxMemoryBytes) {
            throw new MemoryQuotaExceededException(
                String.format("Tenant %s memory quota exceeded: trying to allocate %d bytes, " +
                    "already used %d bytes, max %d bytes",
                    tenantId, size, currentUsed, maxMemoryBytes)
            );
        }

        // 分配直接内存
        ByteBuffer buffer;
        try {
            buffer = ByteBuffer.allocateDirect(size);
        } catch (OutOfMemoryError e) {
            throw new MemoryQuotaExceededException(
                "System memory exhausted when allocating " + size + " bytes for tenant " + tenantId, e
            );
        }

        // 更新统计
        usedMemory.addAndGet(size);
        String allocationId = UUID.randomUUID().toString();
        AllocationInfo info = new AllocationInfo(allocationId, size, Thread.currentThread().getName());
        allocations.put(allocationId, info);

        // 注册清理器
        TrackedByteBuffer tracked = new TrackedByteBuffer(buffer, allocationId, size, this);
        cleaner.register(tracked, new CleanupAction(allocationId, size, this));

        // 检查告警阈值
        checkWarningThreshold();

        return tracked;
    }

    /**
     * 分配并清零内存
     */
    public ByteBuffer allocateZeroed(int size) throws MemoryQuotaExceededException {
        ByteBuffer buffer = allocate(size);
        buffer.put(new byte[size]);
        buffer.flip();
        return buffer;
    }

    /**
     * 释放内存
     * 
     * @param allocationId 分配ID
     * @param size 分配大小
     */
    void free(String allocationId, long size) {
        AllocationInfo removed = allocations.remove(allocationId);
        if (removed != null) {
            usedMemory.addAndGet(-size);
            removed.markFreed();
        }
    }

    /**
     * 手动释放 ByteBuffer
     */
    public void free(ByteBuffer buffer) {
        if (buffer instanceof TrackedByteBuffer) {
            ((TrackedByteBuffer) buffer).free();
        } else if (buffer.isDirect()) {
            // 对于非追踪的直接缓冲区，尝试释放
            try {
                ((sun.nio.ch.DirectBuffer) buffer).cleaner().clean();
            } catch (Exception e) {
                // 忽略释放失败
            }
        }
    }

    /**
     * 检查是否有足够内存
     */
    public boolean canAllocate(long size) {
        return usedMemory.get() + size <= maxMemoryBytes;
    }

    /**
     * 获取剩余可用内存
     */
    public long getAvailableMemory() {
        return maxMemoryBytes - usedMemory.get();
    }

    /**
     * 获取内存统计
     */
    public MemoryStats getStats() {
        // 缓存 1 秒
        long now = System.currentTimeMillis();
        MemoryStats cached = statsCache.get();
        if (cached != null && now - lastStatsUpdate < 1000) {
            return cached;
        }

        long used = usedMemory.get();
        double usagePercent = maxMemoryBytes > 0 ? (double) used / maxMemoryBytes : 0;
        boolean warning = usagePercent >= WARNING_THRESHOLD;

        MemoryStats stats = new MemoryStats(
            tenantId,
            maxMemoryBytes,
            used,
            maxMemoryBytes - used,
            usagePercent,
            allocations.size(),
            warning,
            findPotentialLeaks()
        );

        statsCache.set(stats);
        lastStatsUpdate = now;
        return stats;
    }

    /**
     * 查找潜在的内存泄漏
     */
    private Map<String, Long> findPotentialLeaks() {
        Map<String, Long> leaks = new ConcurrentHashMap<>();
        long now = System.currentTimeMillis();
        long LEAK_THRESHOLD_MS = 5 * 60 * 1000; // 5 分钟

        for (AllocationInfo info : allocations.values()) {
            if (!info.isFreed() && (now - info.getAllocationTime()) > LEAK_THRESHOLD_MS) {
                leaks.put(info.getAllocationId(), now - info.getAllocationTime());
            }
        }

        return leaks;
    }

    /**
     * 检查告警阈值
     */
    private void checkWarningThreshold() {
        double usage = (double) usedMemory.get() / maxMemoryBytes;
        if (usage >= WARNING_THRESHOLD) {
            // 可以在这里触发告警
            System.err.printf("WARNING: Tenant %s memory usage %.1f%% (threshold: %.1f%%)%n",
                tenantId, usage * 100, WARNING_THRESHOLD * 100);
        }
    }

    /**
     * 强制 GC 并释放未使用的直接内存
     */
    public void compact() {
        System.gc();
        // 触发清理器运行
        cleaner.notify();
    }

    // ============ 内部类 ============

    /**
     * 分配信息
     */
    private static class AllocationInfo {
        private final String allocationId;
        private final long size;
        private final String threadName;
        private final long allocationTime;
        private final StackTraceElement[] stackTrace;
        private volatile boolean freed = false;

        AllocationInfo(String allocationId, long size, String threadName) {
            this.allocationId = allocationId;
            this.size = size;
            this.threadName = threadName;
            this.allocationTime = System.currentTimeMillis();
            this.stackTrace = Thread.currentThread().getStackTrace();
        }

        String getAllocationId() { return allocationId; }
        long getSize() { return size; }
        String getThreadName() { return threadName; }
        long getAllocationTime() { return allocationTime; }
        StackTraceElement[] getStackTrace() { return stackTrace; }
        boolean isFreed() { return freed; }
        void markFreed() { this.freed = true; }
    }

    /**
     * 清理动作
     */
    private record CleanupAction(String allocationId, long size, TenantMemoryPool pool) implements Runnable {
        @Override
        public void run() {
            pool.free(allocationId, size);
        }
    }

    /**
     * 内存统计
     */
    public record MemoryStats(
        String tenantId,
        long maxBytes,
        long usedBytes,
        long availableBytes,
        double usagePercent,
        int allocationCount,
        boolean warning,
        Map<String, Long> potentialLeaks
    ) {
        public String format() {
            return String.format(
                "Memory Stats for %s:%n" +
                "  Max: %,d MB%n" +
                "  Used: %,d MB (%.1f%%)%n" +
                "  Available: %,d MB%n" +
                "  Allocations: %d%n" +
                "  Warning: %s%n" +
                "  Potential Leaks: %d%n",
                tenantId,
                maxBytes / (1024 * 1024),
                usedBytes / (1024 * 1024),
                usagePercent * 100,
                availableBytes / (1024 * 1024),
                allocationCount,
                warning ? "YES" : "NO",
                potentialLeaks.size()
            );
        }
    }
}
