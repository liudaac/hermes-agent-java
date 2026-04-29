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
        
        ByteBuffer buffer = pool.allocate(size);
        assertNotNull(buffer);
        assertEquals(size, buffer.capacity());
        
        TenantMemoryPool.MemoryStats stats = pool.getStats();
        assertEquals(size, stats.usedBytes());
        assertEquals(1, stats.allocationCount());
        
        // 释放内存
        pool.free(buffer);
        
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
        ByteBuffer buf1 = pool.allocate(1024 * 1024); // 1MB
        ByteBuffer buf2 = pool.allocate(2 * 1024 * 1024); // 2MB
        
        TenantMemoryPool.MemoryStats stats = pool.getStats();
        assertEquals(3 * 1024 * 1024, stats.usedBytes());
        assertEquals(2, stats.allocationCount());
        
        // 释放一个
        pool.free(buf1);
        
        stats = pool.getStats();
        assertEquals(2 * 1024 * 1024, stats.usedBytes());
        assertEquals(1, stats.allocationCount());
        
        // 清理
        pool.free(buf2);
    }

    @Test
    void testWarningThreshold() {
        // 分配超过 80% 应该触发警告
        long warningSize = (long) (POOL_SIZE * 0.85);
        
        ByteBuffer buffer = pool.allocate((int) warningSize);
        
        TenantMemoryPool.MemoryStats stats = pool.getStats();
        assertTrue(stats.warning());
        
        pool.free(buffer);
    }

    @Test
    void testMemoryLeakDetection() throws InterruptedException {
        // 分配但不释放
        ByteBuffer buffer = pool.allocate(1024);
        
        // 模拟长时间不释放（实际测试中不会真的等待 5 分钟）
        // 这里我们只是测试接口存在
        TenantMemoryPool.MemoryStats stats = pool.getStats();
        assertNotNull(stats.potentialLeaks());
        
        pool.free(buffer);
    }

    @Test
    void testTrackedByteBuffer() {
        ByteBuffer buffer = pool.allocate(1024);
        
        assertTrue(buffer instanceof TrackedByteBuffer);
        TrackedByteBuffer tracked = (TrackedByteBuffer) buffer;
        
        assertNotNull(tracked.getAllocationId());
        assertEquals(1024, tracked.getSize());
        assertFalse(tracked.isFreed());
        
        tracked.free();
        assertTrue(tracked.isFreed());
    }

    @Test
    void testDoubleFree() {
        ByteBuffer buffer = pool.allocate(1024);
        
        pool.free(buffer);
        
        // 双重释放不应该抛出异常
        assertDoesNotThrow(() -> pool.free(buffer));
    }

    @Test
    void testAllocateZeroed() {
        ByteBuffer buffer = pool.allocateZeroed(1024);
        
        // 验证所有字节都是 0
        byte[] bytes = new byte[1024];
        buffer.get(bytes);
        for (byte b : bytes) {
            assertEquals(0, b);
        }
        
        pool.free(buffer);
    }
}
