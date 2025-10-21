package com.metalrender.util;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicLong;

public class BufferArena {
  private final long capacityBytes;
  private final AtomicLong used;
  private final int blockSize;

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
    long prev = this.used.get();
    if (prev + bytes > this.capacityBytes) {
      return -1L;
    } else {
      this.used.addAndGet(bytes);
      return prev;
    }
  }

  public void free(long handle, long bytes) { this.used.addAndGet(-bytes); }

  public ByteBuffer acquire() {
    return ByteBuffer.allocateDirect(this.blockSize)
        .order(ByteOrder.LITTLE_ENDIAN);
  }

  public void release(ByteBuffer buf) {}

  public long getUsedBytes() { return this.used.get(); }

  public long getCapacityBytes() { return this.capacityBytes; }

  public long upload(ByteBuffer data) { return 0L; }
}
