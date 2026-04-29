package com.nousresearch.hermes.tenant.sandbox;

import java.lang.ref.Cleaner;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 租户内存池 - JVM内存隔离
 * 
 * 提供租户级别的内存配额管理：
 * - 使用堆外内存（Direct ByteBuffer）实现隔离
 * - 精确追踪每个租户内存使用量
 * - 超出配额时快速失败
 * - 自动清理和监控
 */
public class TenantMemoryPool {

    private static final Cleaner cleaner = Cleaner.create();
    private static final long DEFAULT_MAX_MEMORY = 256 * 1024 * 1024L; // 256MB

    private final String tenantId;
    private final long maxMemoryBytes;
    private final AtomicLong usedMemoryBytes;
    private final Map<String, Allocation> allocations;
    private final MemoryStats stats;

    /**
     * 内存分配记录
     */
    private static class Allocation {
        final String id;
        final long size;
        final long timestamp;
        final String stackTrace;

        Allocation(String id, long size) {
            this.id = id;
            this.size = size;
            this.timestamp = System.currentTimeMillis();
            // 记录分配时的堆栈，便于调试内存泄漏
            this.stackTrace = getStackTrace();
        }

        private static String getStackTrace() {
            StringBuilder sb = new StringBuilder();
            StackTraceElement[] stack = Thread.currentThread().getStackTrace();
            // 跳过前4个（Thread.getStackTrace, Allocation.<init>, TenantMemoryPool.allocate, 调用者）
            for (int i = 4; i < Math.min(stack.length, 10); i++) {
                sb.append("  at ").append(stack[i]).append("\n");
            }
            return sb.toString();
        }
    }

    public TenantMemoryPool(String tenantId) {
        this(tenantId, DEFAULT_MAX_MEMORY);
    }

    public TenantMemoryPool(String tenantId, long maxMemoryBytes) {
        this.tenantId = tenantId;
        this.maxMemoryBytes = maxMemoryBytes;
        this.usedMemoryBytes = new AtomicLong(0);
        this.allocations = new ConcurrentHashMap<>();
        this.stats = new MemoryStats();
    }

    /**
     * 分配指定大小的内存
     * 
     * @param size 字节数
     * @return 分配的ByteBuffer
     * @throws MemoryQuotaExceededException 如果超出配额
     */
    public ByteBuffer allocate(int size) throws MemoryQuotaExceededException {
        return allocate(size, true);
    }

    /**
     * 分配内存（是否清零）
     */
    public ByteBuffer allocate(int size, boolean clear) throws MemoryQuotaExceededException {
        if (size <= 0) {
            throw new IllegalArgumentException("Size must be positive: " + size);
        }

        // 检查配额
        long currentUsed = usedMemoryBytes.get();
        if (currentUsed + size > maxMemoryBytes) {
            throw new MemoryQuotaExceededException(
                String.format("Tenant %s memory quota exceeded: requested %d bytes, " +
                    "available %d bytes (max: %d, used: %d)",
                    tenantId, size, maxMemoryBytes - currentUsed, maxMemoryBytes, currentUsed)
            );
        }

        // 尝试分配
        if (!usedMemoryBytes.compareAndSet(currentUsed, currentUsed + size)) {
            // 并发冲突，重试
            return allocate(size, clear);
        }

        try {
            // 分配直接内存
            ByteBuffer buffer = ByteBuffer.allocateDirect(size);
            if (clear) {
                buffer.clear();
            }

            // 记录分配
            String allocationId = UUID.randomUUID().toString();
            Allocation allocation = new Allocation(allocationId, size);
            allocations.put(allocationId, allocation);

            // 创建追踪包装器
            TrackedByteBuffer tracked = new TrackedByteBuffer(buffer, allocationId, size, this);
            
            // 注册Cleaner确保释放
            cleaner.register(tracked, new CleanupTask(allocationId, this));

            // 更新统计
            stats.recordAllocation(size);

            return tracked;

        } catch (OutOfMemoryError e) {
            // 回滚已增加的使用量
            usedMemoryBytes.addAndGet(-size);
            throw new MemoryQuotaExceededException(
                "System out of memory when allocating for tenant: " + tenantId, e
            );
        }
    }

    /**
     * 分配内存映射文件
     */
    public MappedByteBuffer allocateMapped(java.io.FileChannel channel, 
                                           java.nio.channels.FileChannel.MapMode mode,
                                           long position, long size) throws MemoryQuotaExceededException {
        // 检查配额
        long currentUsed = usedMemoryBytes.get();
        if (currentUsed + size > maxMemoryBytes) {
            throw new MemoryQuotaExceededException(
                String.format("Tenant %s memory quota exceeded for mapped file: " +
                    "requested %d bytes, available %d bytes",
                    tenantId, size, maxMemoryBytes - currentUsed)
            );
        }

        try {
            MappedByteBuffer buffer = channel.map(mode, position, size);
            
            usedMemoryBytes.addAndGet(size);
            String allocationId = UUID.randomUUID().toString();
            allocations.put(allocationId, new Allocation(allocationId, size));

            // 内存映射文件需要特殊处理，返回包装器
            return new TrackedMappedByteBuffer(buffer, allocationId, size, this);

        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to map file", e);
        }
    }

    /**
     * 释放内存
     */
    void free(String allocationId) {
        Allocation allocation = allocations.remove(allocationId);
        if (allocation != null) {
            usedMemoryBytes.addAndGet(-allocation.size);
            stats.recordDeallocation(allocation.size);
        }
    }

    /**
     * 强制清理所有分配
     */
    public void clear() {
        allocations.clear();
        usedMemoryBytes.set(0);
        stats.reset();
    }

    // ============ 查询方法 ============

    public long getMaxMemoryBytes() {
        return maxMemoryBytes;
    }

    public long getUsedMemoryBytes() {
        return usedMemoryBytes.get();
    }

    public long getAvailableMemoryBytes() {
        return maxMemoryBytes - usedMemoryBytes.get();
    }

    public double getUsagePercentage() {
        return (double) usedMemoryBytes.get() / maxMemoryBytes * 100;
    }

    public int getAllocationCount() {
        return allocations.size();
    }

    public MemoryStats getStats() {
        return stats;
    }

    /**
     * 检查是否接近配额（默认90%）
     */
    public boolean isNearQuota() {
        return getUsagePercentage() > 90;
    }

    public boolean isNearQuota(double threshold) {
        return getUsagePercentage() > threshold;
    }

    /**
     * 获取内存使用报告（用于调试）
     */
    public String getMemoryReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Tenant Memory Report: ").append(tenantId).append(" ===\n");
        sb.append(String.format("Max: %.2f MB\n", maxMemoryBytes / (1024.0 * 1024)));
        sb.append(String.format("Used: %.2f MB (%.1f%%)\n", 
            getUsedMemoryBytes() / (1024.0 * 1024), getUsagePercentage()));
        sb.append(String.format("Available: %.2f MB\n", 
            getAvailableMemoryBytes() / (1024.0 * 1024)));
        sb.append(String.format("Allocations: %d\n", getAllocationCount()));
        
        if (!allocations.isEmpty()) {
            sb.append("\nActive Allocations:\n");
            allocations.values().stream()
                .sorted((a, b) -> Long.compare(b.size, a.size))
                .limit(10)
                .forEach(a -> {
                    sb.append(String.format("  %s: %.2f MB\n", 
                        a.id.substring(0, 8), a.size / (1024.0 * 1024)));
                });
        }
        
        sb.append(stats.toString());
        return sb.toString();
    }

    // ============ 内部类 ============

    /**
     * 内存统计
     */
    public static class MemoryStats {
        private final AtomicLong totalAllocated = new AtomicLong(0);
        private final AtomicLong totalDeallocated = new AtomicLong(0);
        private final AtomicLong peakUsage = new AtomicLong(0);
        private final AtomicLong allocationCount = new AtomicLong(0);

        void recordAllocation(long size) {
            totalAllocated.addAndGet(size);
            allocationCount.incrementAndGet();
            // 峰值可能在并发下不准确，但可接受
        }

        void recordDeallocation(long size) {
            totalDeallocated.addAndGet(size);
        }

        void reset() {
            totalAllocated.set(0);
            totalDeallocated.set(0);
            peakUsage.set(0);
            allocationCount.set(0);
        }

        public long getTotalAllocated() { return totalAllocated.get(); }
        public long getTotalDeallocated() { return totalDeallocated.get(); }
        public long getCurrentUsage() { return totalAllocated.get() - totalDeallocated.get(); }
        public long getPeakUsage() { return peakUsage.get(); }
        public long getAllocationCount() { return allocationCount.get(); }

        @Override
        public String toString() {
            return String.format("\nStats: allocated=%.2f MB, deallocated=%.2f MB, count=%d",
                totalAllocated.get() / (1024.0 * 1024),
                totalDeallocated.get() / (1024.0 * 1024),
                allocationCount.get());
        }
    }

    /**
     * 清理任务（用于Cleaner）
     */
    private static class CleanupTask implements Runnable {
        private final String allocationId;
        private final TenantMemoryPool pool;

        CleanupTask(String allocationId, TenantMemoryPool pool) {
            this.allocationId = allocationId;
            this.pool = pool;
        }

        @Override
        public void run() {
            pool.free(allocationId);
        }
    }
}
