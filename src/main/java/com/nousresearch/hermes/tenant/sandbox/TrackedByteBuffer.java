package com.nousresearch.hermes.tenant.sandbox;

import sun.misc.Cleaner;
import sun.nio.ch.DirectBuffer;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;

/**
 * 带追踪的 ByteBuffer 包装器
 * 
 * 自动管理内存释放，当缓冲区不再被引用时自动释放配额
 */
public class TrackedByteBuffer extends ByteBuffer {

    private final ByteBuffer delegate;
    private final String allocationId;
    private final long size;
    private final TenantMemoryPool pool;
    private volatile boolean freed = false;

    TrackedByteBuffer(ByteBuffer delegate, String allocationId, long size, TenantMemoryPool pool) {
        super(-1, 0, delegate.capacity(), delegate.capacity(), null, 0);
        this.delegate = delegate;
        this.allocationId = allocationId;
        this.size = size;
        this.pool = pool;
    }

    public String getAllocationId() {
        return allocationId;
    }

    public long getSize() {
        return size;
    }

    /**
     * 主动释放内存（推荐在使用完毕后调用）
     */
    public void free() {
        if (!freed) {
            freed = true;
            // 释放直接内存
            if (delegate instanceof DirectBuffer) {
                Cleaner cleaner = ((DirectBuffer) delegate).cleaner();
                if (cleaner != null) {
                    cleaner.clean();
                }
            }
            pool.free(allocationId);
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            free();
        } finally {
            super.finalize();
        }
    }

    // ============ ByteBuffer 委托方法 ============

    @Override
    public ByteBuffer slice() {
        return delegate.slice();
    }

    @Override
    public ByteBuffer duplicate() {
        return delegate.duplicate();
    }

    @Override
    public ByteBuffer asReadOnlyBuffer() {
        return delegate.asReadOnlyBuffer();
    }

    @Override
    public byte get() {
        return delegate.get();
    }

    @Override
    public ByteBuffer put(byte b) {
        return delegate.put(b);
    }

    @Override
    public byte get(int index) {
        return delegate.get(index);
    }

    @Override
    public ByteBuffer put(int index, byte b) {
        return delegate.put(index, b);
    }

    @Override
    public ByteBuffer get(byte[] dst, int offset, int length) {
        return delegate.get(dst, offset, length);
    }

    @Override
    public ByteBuffer put(ByteBuffer src) {
        return delegate.put(src);
    }

    @Override
    public ByteBuffer put(byte[] src, int offset, int length) {
        return delegate.put(src, offset, length);
    }

    @Override
    public ByteBuffer compact() {
        return delegate.compact();
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
        return delegate.getChar();
    }

    @Override
    public ByteBuffer putChar(char value) {
        return delegate.putChar(value);
    }

    @Override
    public char getChar(int index) {
        return delegate.getChar(index);
    }

    @Override
    public ByteBuffer putChar(int index, char value) {
        return delegate.putChar(index, value);
    }

    @Override
    public short getShort() {
        return delegate.getShort();
    }

    @Override
    public ByteBuffer putShort(short value) {
        return delegate.putShort(value);
    }

    @Override
    public short getShort(int index) {
        return delegate.getShort(index);
    }

    @Override
    public ByteBuffer putShort(int index, short value) {
        return delegate.putShort(index, value);
    }

    @Override
    public int getInt() {
        return delegate.getInt();
    }

    @Override
    public ByteBuffer putInt(int value) {
        return delegate.putInt(value);
    }

    @Override
    public int getInt(int index) {
        return delegate.getInt(index);
    }

    @Override
    public ByteBuffer putInt(int index, int value) {
        return delegate.putInt(index, value);
    }

    @Override
    public long getLong() {
        return delegate.getLong();
    }

    @Override
    public ByteBuffer putLong(long value) {
        return delegate.putLong(value);
    }

    @Override
    public long getLong(int index) {
        return delegate.getLong(index);
    }

    @Override
    public ByteBuffer putLong(int index, long value) {
        return delegate.putLong(index, value);
    }

    @Override
    public float getFloat() {
        return delegate.getFloat();
    }

    @Override
    public ByteBuffer putFloat(float value) {
        return delegate.putFloat(value);
    }

    @Override
    public float getFloat(int index) {
        return delegate.getFloat(index);
    }

    @Override
    public ByteBuffer putFloat(int index, float value) {
        return delegate.putFloat(index, value);
    }

    @Override
    public double getDouble() {
        return delegate.getDouble();
    }

    @Override
    public ByteBuffer putDouble(double value) {
        return delegate.putDouble(value);
    }

    @Override
    public double getDouble(int index) {
        return delegate.getDouble(index);
    }

    @Override
    public ByteBuffer putDouble(int index, double value) {
        return delegate.putDouble(index, value);
    }

    @Override
    public ByteOrder order() {
        return delegate.order();
    }

    @Override
    public ByteBuffer order(ByteOrder bo) {
        return delegate.order(bo);
    }

    // Buffer 方法委托
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
        return delegate.position(newPosition);
    }

    @Override
    public int limit() {
        return delegate.limit();
    }

    @Override
    public ByteBuffer limit(int newLimit) {
        return delegate.limit(newLimit);
    }

    @Override
    public ByteBuffer mark() {
        return delegate.mark();
    }

    @Override
    public ByteBuffer reset() {
        return delegate.reset();
    }

    @Override
    public ByteBuffer clear() {
        return delegate.clear();
    }

    @Override
    public ByteBuffer flip() {
        return delegate.flip();
    }

    @Override
    public ByteBuffer rewind() {
        return delegate.rewind();
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
}

/**
 * 带追踪的 MappedByteBuffer 包装器
 */
class TrackedMappedByteBuffer extends MappedByteBuffer {

    private final MappedByteBuffer delegate;
    private final String allocationId;
    private final long size;
    private final TenantMemoryPool pool;
    private volatile boolean freed = false;

    TrackedMappedByteBuffer(MappedByteBuffer delegate, String allocationId, long size, TenantMemoryPool pool) {
        super(-1, 0, delegate.capacity(), delegate.capacity(), null, 0);
        this.delegate = delegate;
        this.allocationId = allocationId;
        this.size = size;
        this.pool = pool;
    }

    public void free() {
        if (!freed) {
            freed = true;
            // 强制解除内存映射
            ((sun.nio.ch.DirectBuffer) delegate).cleaner().clean();
            pool.free(allocationId);
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            free();
        } finally {
            super.finalize();
        }
    }

    // MappedByteBuffer 方法
    @Override
    public boolean isLoaded() {
        return delegate.isLoaded();
    }

    @Override
    public MappedByteBuffer load() {
        return delegate.load();
    }

    @Override
    public MappedByteBuffer force() {
        return delegate.force();
    }

    @Override
    public ByteBuffer slice() {
        return delegate.slice();
    }

    @Override
    public ByteBuffer duplicate() {
        return delegate.duplicate();
    }

    @Override
    public ByteBuffer asReadOnlyBuffer() {
        return delegate.asReadOnlyBuffer();
    }

    @Override
    public byte get() {
        return delegate.get();
    }

    @Override
    public ByteBuffer put(byte b) {
        return delegate.put(b);
    }

    @Override
    public byte get(int index) {
        return delegate.get(index);
    }

    @Override
    public ByteBuffer put(int index, byte b) {
        return delegate.put(index, b);
    }

    @Override
    public ByteBuffer get(byte[] dst, int offset, int length) {
        return delegate.get(dst, offset, length);
    }

    @Override
    public ByteBuffer put(ByteBuffer src) {
        return delegate.put(src);
    }

    @Override
    public ByteBuffer put(byte[] src, int offset, int length) {
        return delegate.put(src, offset, length);
    }

    @Override
    public ByteBuffer compact() {
        return delegate.compact();
    }

    @Override
    public boolean isDirect() {
        return delegate.isDirect();
    }

    @Override
    public char getChar() {
        return delegate.getChar();
    }

    @Override
    public ByteBuffer putChar(char value) {
        return delegate.putChar(value);
    }

    @Override
    public char getChar(int index) {
        return delegate.getChar(index);
    }

    @Override
    public ByteBuffer putChar(int index, char value) {
        return delegate.putChar(index, value);
    }

    @Override
    public short getShort() {
        return delegate.getShort();
    }

    @Override
    public ByteBuffer putShort(short value) {
        return delegate.putShort(value);
    }

    @Override
    public short getShort(int index) {
        return delegate.getShort(index);
    }

    @Override
    public ByteBuffer putShort(int index, short value) {
        return delegate.putShort(index, value);
    }

    @Override
    public int getInt() {
        return delegate.getInt();
    }

    @Override
    public ByteBuffer putInt(int value) {
        return delegate.putInt(value);
    }

    @Override
    public int getInt(int index) {
        return delegate.getInt(index);
    }

    @Override
    public ByteBuffer putInt(int index, int value) {
        return delegate.putInt(index, value);
    }

    @Override
    public long getLong() {
        return delegate.getLong();
    }

    @Override
    public ByteBuffer putLong(long value) {
        return delegate.putLong(value);
    }

    @Override
    public long getLong(int index) {
        return delegate.getLong(index);
    }

    @Override
    public ByteBuffer putLong(int index, long value) {
        return delegate.putLong(index, value);
    }

    @Override
    public float getFloat() {
        return delegate.getFloat();
    }

    @Override
    public ByteBuffer putFloat(float value) {
        return delegate.putFloat(value);
    }

    @Override
    public float getFloat(int index) {
        return delegate.getFloat(index);
    }

    @Override
    public ByteBuffer putFloat(int index, float value) {
        return delegate.putFloat(index, value);
    }

    @Override
    public double getDouble() {
        return delegate.getDouble();
    }

    @Override
    public ByteBuffer putDouble(double value) {
        return delegate.putDouble(value);
    }

    @Override
    public double getDouble(int index) {
        return delegate.getDouble(index);
    }

    @Override
    public ByteBuffer putDouble(int index, double value) {
        return delegate.putDouble(index, value);
    }

    @Override
    public ByteOrder order() {
        return delegate.order();
    }

    @Override
    public ByteBuffer order(ByteOrder bo) {
        return delegate.order(bo);
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
        return delegate.position(newPosition);
    }

    @Override
    public int limit() {
        return delegate.limit();
    }

    @Override
    public ByteBuffer limit(int newLimit) {
        return delegate.limit(newLimit);
    }

    @Override
    public ByteBuffer mark() {
        return delegate.mark();
    }

    @Override
    public ByteBuffer reset() {
        return delegate.reset();
    }

    @Override
    public ByteBuffer clear() {
        return delegate.clear();
    }

    @Override
    public ByteBuffer flip() {
        return delegate.flip();
    }

    @Override
    public ByteBuffer rewind() {
        return delegate.rewind();
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
}
