package com.metalrender.util;

import com.metalrender.nativebridge.NativeBridge;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

public final class PersistentBufferArena {
  private final AtomicInteger cursor = new AtomicInteger();
  private ByteBuffer mapped;
  private int capacity;
  private int alignment = 256;
  private long contextHandle;
  private int framesSinceLastClear = 0;
  private static final int CLEAR_FREQUENCY = 15;

  public boolean initialize(long ctx) {
    if (ctx == 0L) {
      return false;
    }
    ByteBuffer buffer = NativeBridge.nMapPersistentBuffer(ctx);
    if (buffer == null) {
      return false;
    }
    this.contextHandle = ctx;
    this.mapped = buffer;
    this.capacity = NativeBridge.nPersistentCapacity(ctx);
    this.alignment = Math.max(1, NativeBridge.nPersistentAlign(ctx));
    this.cursor.set(0);
    return true;
  }

  public void reset() { this.cursor.set(0); }

  public void advanceFrame() {
    framesSinceLastClear++;
    if (framesSinceLastClear >= CLEAR_FREQUENCY) {
      if (this.cursor.get() > this.capacity * 0.6) {
        this.reset();
        framesSinceLastClear = 0;
      }
    }
  }

  public int capacity() { return this.capacity; }

  public int alignment() { return this.alignment; }

  public ByteBuffer buffer() { return this.mapped; }

  public synchronized int allocate(int length) {
    if (this.mapped == null || length <= 0) {
      return -1;
    }
    int aligned = align(length, this.alignment);
    int current = this.cursor.get();

    if (current + aligned > this.capacity) {
      this.cursor.set(aligned);
      current = 0;
    } else {
      this.cursor.addAndGet(aligned);
    }

    NativeBridge.nPersistentAdvance(this.contextHandle, this.cursor.get());
    return current;
  }

  private static int align(int value, int alignment) {
    int mask = alignment - 1;
    return (value + mask) & ~mask;
  }
}
