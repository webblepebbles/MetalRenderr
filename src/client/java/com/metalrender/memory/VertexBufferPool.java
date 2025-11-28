package com.metalrender.memory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class VertexBufferPool {
  private static final VertexBufferPool INSTANCE = new VertexBufferPool();

  private final Map<Integer, ConcurrentLinkedQueue<ByteBuffer>> buckets =
      new ConcurrentHashMap<>();

  private VertexBufferPool() {}

  public static VertexBufferPool shared() {
    return INSTANCE;
  }


  public ByteBuffer acquire(int requestedCapacity) {
    if (requestedCapacity <= 0) {
      requestedCapacity = MetalVertexLayout.STRIDE;
    }
    int bucket = bucketSize(requestedCapacity);
    ConcurrentLinkedQueue<ByteBuffer> queue = buckets.computeIfAbsent(bucket,
        ignored -> new ConcurrentLinkedQueue<>());
    ByteBuffer buffer = queue.poll();
    if (buffer == null) {
      buffer = ByteBuffer.allocateDirect(bucket).order(ByteOrder.LITTLE_ENDIAN);
    } else {
      buffer.clear();
      buffer.order(ByteOrder.LITTLE_ENDIAN);
    }
    buffer.limit(bucket);
    return buffer;
  }


  public void release(ByteBuffer buffer) {
    if (buffer == null || !buffer.isDirect()) {
      return;
    }
    int bucket = bucketSize(buffer.capacity());
    ConcurrentLinkedQueue<ByteBuffer> queue = buckets.computeIfAbsent(bucket,
        ignored -> new ConcurrentLinkedQueue<>());
    buffer.clear();
    queue.offer(buffer);
  }

  private static int bucketSize(int capacity) {
    int size = Math.max(MetalVertexLayout.STRIDE,
        Integer.highestOneBit(Math.max(capacity, 1) - 1) << 1);
    if (size < capacity) {
      size = capacity;
    }
    return size;
  }
}
