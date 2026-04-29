package com.nousresearch.hermes.tenant.sandbox;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.ShortBuffer;

/**
 * 带追踪的 ByteBuffer 包装器
 * 
 * 包装直接内存 ByteBuffer，实现：
 * - 自动释放追踪
 * - 防止重复释放
 * - 访问已释放缓冲区检测
 */
public class TrackedByteBuffer extends ByteBuffer {

    private final ByteBuffer delegate;
    private final String allocationId;
    private final long size;
    private final TenantMemoryPool pool;
    private volatile boolean freed = false;

    TrackedByteBuffer(ByteBuffer delegate, String allocationId, long size, TenantMemoryPool pool) {
        super(-1, 0, 0, 0, null, 0);
        this.delegate = delegate;
        this.allocationId = allocationId;
        this.size = size;
        this.pool = pool;
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
        try {
            ((sun.nio.ch.DirectBuffer) delegate).cleaner().clean();
        } catch (Exception e) {
            // 忽略释放失败
        }

        // 更新池统计
        pool.free(allocationId, size);
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

    private void checkFreed() {
        if (freed) {
            throw new IllegalStateException("Buffer has been freed: " + allocationId);
        }
    }

    // ============ ByteBuffer 代理方法 ============

    @Override
    public ByteBuffer slice() {
        checkFreed();
        return delegate.slice();
    }

    @Override
    public ByteBuffer duplicate() {
        checkFreed();
        return delegate.duplicate();
    }

    @Override
    public ByteBuffer asReadOnlyBuffer() {
        checkFreed();
        return delegate.asReadOnlyBuffer();
    }

    @Override
    public byte get() {
        checkFreed();
        return delegate.get();
    }

    @Override
    public ByteBuffer put(byte b) {
        checkFreed();
        delegate.put(b);
        return this;
    }

    @Override
    public byte get(int index) {
        checkFreed();
        return delegate.get(index);
    }

    @Override
    public ByteBuffer put(int index, byte b) {
        checkFreed();
        delegate.put(index, b);
        return this;
    }

    @Override
    public ByteBuffer get(byte[] dst, int offset, int length) {
        checkFreed();
        delegate.get(dst, offset, length);
        return this;
    }

    @Override
    public ByteBuffer put(ByteBuffer src) {
        checkFreed();
        delegate.put(src);
        return this;
    }

    @Override
    public ByteBuffer put(byte[] src, int offset, int length) {
        checkFreed();
        delegate.put(src, offset, length);
        return this;
    }

    @Override
    public ByteBuffer compact() {
        checkFreed();
        delegate.compact();
        return this;
    }

    @Override
    public boolean isDirect() {
        return delegate.isDirect();
    }

    @Override
    public boolean isReadOnly() {
        return delegate.isReadOnly();
    }

    @Override
    public char getChar() {
        checkFreed();
        return delegate.getChar();
    }

    @Override
    public ByteBuffer putChar(char value) {
        checkFreed();
        delegate.putChar(value);
        return this;
    }

    @Override
    public char getChar(int index) {
        checkFreed();
        return delegate.getChar(index);
    }

    @Override
    public ByteBuffer putChar(int index, char value) {
        checkFreed();
        delegate.putChar(index, value);
        return this;
    }

    @Override
    public CharBuffer asCharBuffer() {
        checkFreed();
        return delegate.asCharBuffer();
    }

    @Override
    public short getShort() {
        checkFreed();
        return delegate.getShort();
    }

    @Override
    public ByteBuffer putShort(short value) {
        checkFreed();
        delegate.putShort(value);
        return this;
    }

    @Override
    public short getShort(int index) {
        checkFreed();
        return delegate.getShort(index);
    }

    @Override
    public ByteBuffer putShort(int index, short value) {
        checkFreed();
        delegate.putShort(index, value);
        return this;
    }

    @Override
    public ShortBuffer asShortBuffer() {
        checkFreed();
        return delegate.asShortBuffer();
    }

    @Override
    public int getInt() {
        checkFreed();
        return delegate.getInt();
    }

    @Override
    public ByteBuffer putInt(int value) {
        checkFreed();
        delegate.putInt(value);
        return this;
    }

    @Override
    public int getInt(int index) {
        checkFreed();
        return delegate.getInt(index);
    }

    @Override
    public ByteBuffer putInt(int index, int value) {
        checkFreed();
        delegate.putInt(index, value);
        return this;
    }

    @Override
    public IntBuffer asIntBuffer() {
        checkFreed();
        return delegate.asIntBuffer();
    }

    @Override
    public long getLong() {
        checkFreed();
        return delegate.getLong();
    }

    @Override
    public ByteBuffer putLong(long value) {
        checkFreed();
        delegate.putLong(value);
        return this;
    }

    @Override
    public long getLong(int index) {
        checkFreed();
        return delegate.getLong(index);
    }

    @Override
    public ByteBuffer putLong(int index, long value) {
        checkFreed();
        delegate.putLong(index, value);
        return this;
    }

    @Override
    public LongBuffer asLongBuffer() {
        checkFreed();
        return delegate.asLongBuffer();
    }

    @Override
    public float getFloat() {
        checkFreed();
        return delegate.getFloat();
    }

    @Override
    public ByteBuffer putFloat(float value) {
        checkFreed();
        delegate.putFloat(value);
        return this;
    }

    @Override
    public float getFloat(int index) {
        checkFreed();
        return delegate.getFloat(index);
    }

    @Override
    public ByteBuffer putFloat(int index, float value) {
        checkFreed();
        delegate.putFloat(index, value);
        return this;
    }

    @Override
    public FloatBuffer asFloatBuffer() {
        checkFreed();
        return delegate.asFloatBuffer();
    }

    @Override
    public double getDouble() {
        checkFreed();
        return delegate.getDouble();
    }

    @Override
    public ByteBuffer putDouble(double value) {
        checkFreed();
        delegate.putDouble(value);
        return this;
    }

    @Override
    public double getDouble(int index) {
        checkFreed();
        return delegate.getDouble(index);
    }

    @Override
    public ByteBuffer putDouble(int index, double value) {
        checkFreed();
        delegate.putDouble(index, value);
        return this;
    }

    @Override
    public DoubleBuffer asDoubleBuffer() {
        checkFreed();
        return delegate.asDoubleBuffer();
    }

    @Override
    public int capacity() {
        return delegate.capacity();
    }

    @Override
    public int position() {
        return delegate.position();
    }

    @Override
    public ByteBuffer position(int newPosition) {
        checkFreed();
        delegate.position(newPosition);
        return this;
    }

    @Override
    public int limit() {
        return delegate.limit();
    }

    @Override
    public ByteBuffer limit(int newLimit) {
        checkFreed();
        delegate.limit(newLimit);
        return this;
    }

    @Override
    public ByteBuffer mark() {
        checkFreed();
        delegate.mark();
        return this;
    }

    @Override
    public ByteBuffer reset() {
        checkFreed();
        delegate.reset();
        return this;
    }

    @Override
    public ByteBuffer clear() {
        checkFreed();
        delegate.clear();
        return this;
    }

    @Override
    public ByteBuffer flip() {
        checkFreed();
        delegate.flip();
        return this;
    }

    @Override
    public ByteBuffer rewind() {
        checkFreed();
        delegate.rewind();
        return this;
    }

    @Override
    public int remaining() {
        return delegate.remaining();
    }

    @Override
    public boolean hasRemaining() {
        return delegate.hasRemaining();
    }

    @Override
    public boolean isReadOnly() {
        return delegate.isReadOnly();
    }

    @Override
    public boolean hasArray() {
        return delegate.hasArray();
    }

    @Override
    public byte[] array() {
        return delegate.array();
    }

    @Override
    public int arrayOffset() {
        return delegate.arrayOffset();
    }

    @Override
    public ByteOrder order() {
        return delegate.order();
    }

    @Override
    public ByteBuffer order(ByteOrder bo) {
        checkFreed();
        delegate.order(bo);
        return this;
    }
}
