package com.metalrender.util;

import com.metalrender.nativebridge.NativeBridge;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;

public final class PersistentBufferArena {
  private ByteBuffer mapped;
  private int capacity;
  private int alignment = 256;
  private long contextHandle;

  // Allocator state
  private final ReentrantLock lock = new ReentrantLock();
  private final NavigableMap<Integer, Integer> freeBlocks = new TreeMap<>();
  private int top = 0; // The end of the used space

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

    this.top = 0;
    this.freeBlocks.clear();
    return true;
  }

  public void reset() {
    lock.lock();
    try {
      this.top = 0;
      this.freeBlocks.clear();
    } finally {
      lock.unlock();
    }
  }

  public int capacity() {
    return this.capacity;
  }

  public int alignment() {
    return this.alignment;
  }

  public ByteBuffer buffer() {
    return this.mapped;
  }

  public int allocate(int length) {
    if (this.mapped == null || length <= 0) {
      return -1;
    }

    int aligned = align(length, this.alignment);

    lock.lock();
    try {
      // 1. Try to find a free block that fits (First Fit)
      for (Map.Entry<Integer, Integer> entry : freeBlocks.entrySet()) {
        int offset = entry.getKey();
        int size = entry.getValue();

        if (size >= aligned) {
          // Found a fit!
          freeBlocks.remove(offset);

          int remaining = size - aligned;
          if (remaining > 0) {
            freeBlocks.put(offset + aligned, remaining);
          }

          return offset;
        }
      }

      // 2. Allocate from top
      if (top + aligned <= capacity) {
        int offset = top;
        top += aligned;
        return offset;
      }

      // 3. Out of memory
      return -1;
    } finally {
      lock.unlock();
    }
  }

  public void free(int offset, int length) {
    if (offset < 0 || length <= 0)
      return;
    int aligned = align(length, this.alignment);

    lock.lock();
    try {
      // Add to free blocks
      freeBlocks.put(offset, aligned);

      // Merge with next block
      Integer nextOffset = freeBlocks.higherKey(offset);
      if (nextOffset != null && nextOffset == offset + aligned) {
        int nextSize = freeBlocks.get(nextOffset);
        freeBlocks.remove(nextOffset);
        freeBlocks.put(offset, aligned + nextSize);
        aligned += nextSize; // Update current block size for previous merge
      }

      // Merge with previous block
      Integer prevOffset = freeBlocks.lowerKey(offset);
      if (prevOffset != null) {
        int prevSize = freeBlocks.get(prevOffset);
        if (prevOffset + prevSize == offset) {
          freeBlocks.remove(offset);
          freeBlocks.put(prevOffset, prevSize + aligned);
        }
      }

      // Optional: If the last block touches 'top', reduce 'top'
      Map.Entry<Integer, Integer> lastEntry = freeBlocks.lastEntry();
      if (lastEntry != null && lastEntry.getKey() + lastEntry.getValue() == top) {
        top = lastEntry.getKey();
        freeBlocks.remove(lastEntry.getKey());
      }

    } finally {
      lock.unlock();
    }
  }

  private static int align(int value, int alignment) {
    int mask = alignment - 1;
    return (value + mask) & ~mask;
  }
}
