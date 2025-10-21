package com.metalrender.util;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;

public final class ObjectPool<T> {
  private final Queue<T> pool = new ConcurrentLinkedQueue<>();
  private final Supplier<T> factory;
  private final int maxSize;
  private volatile int currentSize = 0;

  public ObjectPool(Supplier<T> factory, int maxSize) {
    this.factory = factory;
    this.maxSize = Math.max(8, Math.min(maxSize, 1024));
  }

  public T acquire() {
    T obj = pool.poll();
    if (obj != null) {
      currentSize--;
      return obj;
    }
    return factory.get();
  }

  public void release(T obj) {
    if (obj != null && currentSize < maxSize) {
      pool.offer(obj);
      currentSize++;
    }
  }

  public void clear() {
    pool.clear();
    currentSize = 0;
  }

  public int size() { return currentSize; }

  public int maxSize() { return maxSize; }
}