package com.metalrender.util;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicLong;

public class BufferArena {
  private final long capacityBytes;
  private final AtomicLong used;
  private final int blockSize;
  private final ThreadLocal<ByteBuffer> threadLocalBuffer = new ThreadLocal<>();

  public BufferArena(long capacityBytes) { this(capacityBytes, 4096); }

  public BufferArena(long capacityBytes, int blockSize) {
    this.used = new AtomicLong();
    this.capacityBytes = capacityBytes;
    this.blockSize = Math.max(256, blockSize);
  }

  public boolean canAllocate(long bytes) {
    return this.used.get() + bytes <= this.capacityBytes;
  }

  public long allocate(long bytes) {
    long allocated = this.used.addAndGet(bytes);
    if (allocated > this.capacityBytes) {
      this.used.addAndGet(-bytes);
      return -1L;
    }
    return allocated - bytes;
  }

  public void free(long handle, long bytes) { this.used.addAndGet(-bytes); }

  public ByteBuffer acquire() {
    ByteBuffer buffer = threadLocalBuffer.get();
    if (buffer == null) {
      buffer = ByteBuffer.allocateDirect(this.blockSize)
                   .order(ByteOrder.LITTLE_ENDIAN);
      threadLocalBuffer.set(buffer);
    }
    buffer.clear();
    return buffer;
  }

  public void release(ByteBuffer buf) {}

  public long getUsedBytes() { return this.used.get(); }

  public long getCapacityBytes() { return this.capacityBytes; }

  public long upload(ByteBuffer data) { return 0L; }
}
