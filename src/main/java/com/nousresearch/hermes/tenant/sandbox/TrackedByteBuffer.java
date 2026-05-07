package com.nousresearch.hermes.tenant.sandbox;

import java.lang.ref.Cleaner;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 带追踪的 ByteBuffer 包装器
 * 
 * 包装直接内存 ByteBuffer，实现：
 * - 自动释放追踪
 * - 防止重复释放
 * - 访问已释放缓冲区检测
 * 
 * 注意：Java 17+ 中 ByteBuffer 是密封类，此类使用组合模式而非继承
 */
public final class TrackedByteBuffer {

    private final ByteBuffer delegate;
    private final String allocationId;
    private final long size;
    private final TenantMemoryPool pool;
    private volatile boolean freed = false;
    private final Cleaner.Cleanable cleanable;

    TrackedByteBuffer(ByteBuffer delegate, String allocationId, long size, TenantMemoryPool pool) {
        this.delegate = delegate;
        this.allocationId = allocationId;
        this.size = size;
        this.pool = pool;
        // 注册清理器
        this.cleanable = TenantMemoryPool.getCleaner().register(this, new CleanupAction(allocationId, size, pool, this));
    }

    /**
     * 释放内存
     */
    public void free() {
        if (freed) {
            return;
        }
        freed = true;

        // 释放直接内存
        cleanDirectBuffer(delegate);

        // 更新池统计
        pool.free(allocationId, size);
        
        // 注销 cleaner
        cleanable.clean();
    }

    /**
     * 使用反射清理直接缓冲区
     */
    private static void cleanDirectBuffer(ByteBuffer buffer) {
        if (!buffer.isDirect()) {
            return;
        }
        try {
            java.lang.reflect.Method cleanerMethod = buffer.getClass().getMethod("cleaner");
            cleanerMethod.setAccessible(true);
            Object cleaner = cleanerMethod.invoke(buffer);
            if (cleaner != null) {
                java.lang.reflect.Method cleanMethod = cleaner.getClass().getMethod("clean");
                cleanMethod.setAccessible(true);
                cleanMethod.invoke(cleaner);
            }
        } catch (Exception e) {
            // 忽略释放失败，依赖 GC 最终回收
        }
    }

    /**
     * 检查是否已释放
     */
    public boolean isFreed() {
        return freed;
    }

    /**
     * 获取分配ID
     */
    public String getAllocationId() {
        return allocationId;
    }

    /**
     * 获取分配大小
     */
    public long getSize() {
        return size;
    }

    /**
     * 获取被包装的 ByteBuffer
     */
    public ByteBuffer getDelegate() {
        checkFreed();
        return delegate;
    }

    private void checkFreed() {
        if (freed) {
            throw new IllegalStateException("Buffer has been freed: " + allocationId);
        }
    }

    // ============ 代理方法 ============

    public ByteBuffer slice() {
        checkFreed();
        return delegate.slice();
    }

    public ByteBuffer duplicate() {
        checkFreed();
        return delegate.duplicate();
    }

    public ByteBuffer asReadOnlyBuffer() {
        checkFreed();
        return delegate.asReadOnlyBuffer();
    }

    public byte get() {
        checkFreed();
        return delegate.get();
    }

    public ByteBuffer put(byte b) {
        checkFreed();
        delegate.put(b);
        return delegate;
    }

    public byte get(int index) {
        checkFreed();
        return delegate.get(index);
    }

    public ByteBuffer put(int index, byte b) {
        checkFreed();
        delegate.put(index, b);
        return delegate;
    }

    public ByteBuffer get(byte[] dst, int offset, int length) {
        checkFreed();
        delegate.get(dst, offset, length);
        return delegate;
    }

    public ByteBuffer put(ByteBuffer src) {
        checkFreed();
        delegate.put(src);
        return delegate;
    }

    public ByteBuffer put(byte[] src, int offset, int length) {
        checkFreed();
        delegate.put(src, offset, length);
        return delegate;
    }

    public ByteBuffer compact() {
        checkFreed();
        delegate.compact();
        return delegate;
    }

    public boolean isDirect() {
        return delegate.isDirect();
    }

    public boolean isReadOnly() {
        return delegate.isReadOnly();
    }

    public char getChar() {
        checkFreed();
        return delegate.getChar();
    }

    public ByteBuffer putChar(char value) {
        checkFreed();
        delegate.putChar(value);
        return delegate;
    }

    public char getChar(int index) {
        checkFreed();
        return delegate.getChar(index);
    }

    public ByteBuffer putChar(int index, char value) {
        checkFreed();
        delegate.putChar(index, value);
        return delegate;
    }

    public short getShort() {
        checkFreed();
        return delegate.getShort();
    }

    public ByteBuffer putShort(short value) {
        checkFreed();
        delegate.putShort(value);
        return delegate;
    }

    public short getShort(int index) {
        checkFreed();
        return delegate.getShort(index);
    }

    public ByteBuffer putShort(int index, short value) {
        checkFreed();
        delegate.putShort(index, value);
        return delegate;
    }

    public int getInt() {
        checkFreed();
        return delegate.getInt();
    }

    public ByteBuffer putInt(int value) {
        checkFreed();
        delegate.putInt(value);
        return delegate;
    }

    public int getInt(int index) {
        checkFreed();
        return delegate.getInt(index);
    }

    public ByteBuffer putInt(int index, int value) {
        checkFreed();
        delegate.putInt(index, value);
        return delegate;
    }

    public long getLong() {
        checkFreed();
        return delegate.getLong();
    }

    public ByteBuffer putLong(long value) {
        checkFreed();
        delegate.putLong(value);
        return delegate;
    }

    public long getLong(int index) {
        checkFreed();
        return delegate.getLong(index);
    }

    public ByteBuffer putLong(int index, long value) {
        checkFreed();
        delegate.putLong(index, value);
        return delegate;
    }

    public float getFloat() {
        checkFreed();
        return delegate.getFloat();
    }

    public ByteBuffer putFloat(float value) {
        checkFreed();
        delegate.putFloat(value);
        return delegate;
    }

    public float getFloat(int index) {
        checkFreed();
        return delegate.getFloat(index);
    }

    public ByteBuffer putFloat(int index, float value) {
        checkFreed();
        delegate.putFloat(index, value);
        return delegate;
    }

    public double getDouble() {
        checkFreed();
        return delegate.getDouble();
    }

    public ByteBuffer putDouble(double value) {
        checkFreed();
        delegate.putDouble(value);
        return delegate;
    }

    public double getDouble(int index) {
        checkFreed();
        return delegate.getDouble(index);
    }

    public ByteBuffer putDouble(int index, double value) {
        checkFreed();
        delegate.putDouble(index, value);
        return delegate;
    }

    public int capacity() {
        return delegate.capacity();
    }

    public int position() {
        return delegate.position();
    }

    public ByteBuffer position(int newPosition) {
        checkFreed();
        delegate.position(newPosition);
        return delegate;
    }

    public int limit() {
        return delegate.limit();
    }

    public ByteBuffer limit(int newLimit) {
        checkFreed();
        delegate.limit(newLimit);
        return delegate;
    }

    public ByteBuffer mark() {
        checkFreed();
        delegate.mark();
        return delegate;
    }

    public ByteBuffer reset() {
        checkFreed();
        delegate.reset();
        return delegate;
    }

    public ByteBuffer clear() {
        checkFreed();
        delegate.clear();
        return delegate;
    }

    public ByteBuffer flip() {
        checkFreed();
        delegate.flip();
        return delegate;
    }

    public ByteBuffer rewind() {
        checkFreed();
        delegate.rewind();
        return delegate;
    }

    public int remaining() {
        return delegate.remaining();
    }

    public boolean hasRemaining() {
        return delegate.hasRemaining();
    }

    public boolean hasArray() {
        return delegate.hasArray();
    }

    public byte[] array() {
        return delegate.array();
    }

    public int arrayOffset() {
        return delegate.arrayOffset();
    }

    public ByteOrder order() {
        return delegate.order();
    }

    public ByteBuffer order(ByteOrder bo) {
        checkFreed();
        delegate.order(bo);
        return delegate;
    }

    // ============ 内部类 ============

    private record CleanupAction(String allocationId, long size, TenantMemoryPool pool, TrackedByteBuffer buffer) implements Runnable {
        @Override
        public void run() {
            if (!buffer.isFreed()) {
                pool.free(allocationId, size);
            }
        }
    }
}
