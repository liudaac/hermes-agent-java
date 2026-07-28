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

}
