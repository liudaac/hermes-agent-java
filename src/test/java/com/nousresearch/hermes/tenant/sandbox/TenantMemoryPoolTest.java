package com.nousresearch.hermes.tenant.sandbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TenantMemoryPool 单元测试
 */
class TenantMemoryPoolTest {

    private TenantMemoryPool pool;
    private static final long POOL_SIZE = 10 * 1024 * 1024; // 10MB

    @BeforeEach
    void setUp() {
        pool = new TenantMemoryPool("test-tenant", POOL_SIZE);
    }

    @Test
    void testAllocateAndFree() {
        int size = 1024 * 1024; // 1MB

        TrackedByteBuffer buffer = pool.allocate(size);
        assertNotNull(buffer);
        assertEquals(size, buffer.getSize());

        TenantMemoryPool.MemoryStats stats = pool.getStats();
        assertEquals(size, stats.usedBytes());
        assertEquals(1, stats.allocationCount());

        // 释放内存
        buffer.free();

        stats = pool.getStats();
        assertEquals(0, stats.usedBytes());
    }

    @Test
    void testQuotaExceeded() {
        // 尝试分配超过配额的内存
        long oversizedAllocation = POOL_SIZE + 1;

        assertThrows(
            MemoryQuotaExceededException.class,
            () -> pool.allocate((int) oversizedAllocation)
        );
    }

    @Test
    void testCanAllocate() {
        assertTrue(pool.canAllocate(1024 * 1024)); // 1MB
        assertTrue(pool.canAllocate(5 * 1024 * 1024)); // 5MB
        assertFalse(pool.canAllocate(POOL_SIZE + 1)); // Over limit
    }

    @Test
    void testMultipleAllocations() {
        TrackedByteBuffer buf1 = pool.allocate(1024 * 1024); // 1MB
        TrackedByteBuffer buf2 = pool.allocate(2 * 1024 * 1024); // 2MB

        TenantMemoryPool.MemoryStats stats = pool.getStats();
        assertEquals(3 * 1024 * 1024, stats.usedBytes());
        assertEquals(2, stats.allocationCount());

        // 释放一个
        buf1.free();

        stats = pool.getStats();
        assertEquals(2 * 1024 * 1024, stats.usedBytes());
        assertEquals(1, stats.allocationCount());

        // 清理
        buf2.free();
    }

    @Test
    void testWarningThreshold() {
        // 分配超过 80% 应该触发警告
        long warningSize = (long) (POOL_SIZE * 0.85);

        TrackedByteBuffer buffer = pool.allocate((int) warningSize);

        TenantMemoryPool.MemoryStats stats = pool.getStats();
        assertTrue(stats.warning());

        buffer.free();
    }

    @Test
    void testMemoryLeakDetection() throws InterruptedException {
        // 分配但不释放
        TrackedByteBuffer buffer = pool.allocate(1024);

        // 模拟长时间不释放（实际测试中不会真的等待 5 分钟）
        // 这里我们只是测试接口存在
        TenantMemoryPool.MemoryStats stats = pool.getStats();
        assertNotNull(stats.potentialLeaks());

        buffer.free();
    }

    @Test
    void testTrackedByteBuffer() {
        TrackedByteBuffer buffer = pool.allocate(1024);

        assertNotNull(buffer.getAllocationId());
        assertEquals(1024, buffer.getSize());
        assertFalse(buffer.isFreed());

        buffer.free();
        assertTrue(buffer.isFreed());
    }

    @Test
    void testDoubleFree() {
        TrackedByteBuffer buffer = pool.allocate(1024);

        buffer.free();

        // 双重释放不应该抛出异常
        assertDoesNotThrow(() -> buffer.free());
    }

    @Test
    void testAllocateZeroed() {
        TrackedByteBuffer trackedBuffer = pool.allocateZeroed(1024);
        ByteBuffer buffer = trackedBuffer.getDelegate();

        // 验证所有字节都是 0
        byte[] bytes = new byte[1024];
        buffer.get(bytes);
        for (byte b : bytes) {
            assertEquals(0, b);
        }

        trackedBuffer.free();
    }
}
