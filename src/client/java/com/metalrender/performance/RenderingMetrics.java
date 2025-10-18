package com.metalrender.performance;

import java.util.concurrent.atomic.AtomicLong;

public final class RenderingMetrics {
  private static final AtomicLong totalVerticesQueued = new AtomicLong(0);
  private static final AtomicLong totalDrawCommands = new AtomicLong(0);
  private static final AtomicLong chunksAtLod0 = new AtomicLong(0);
  private static final AtomicLong chunksAtLod1 = new AtomicLong(0);
  private static final AtomicLong chunksAtLod2 = new AtomicLong(0);
  private static final AtomicLong verticesReduced = new AtomicLong(0);

  private RenderingMetrics() {}

  public static void resetFrame() {
    totalVerticesQueued.set(0);
    totalDrawCommands.set(0);
    chunksAtLod0.set(0);
    chunksAtLod1.set(0);
    chunksAtLod2.set(0);
    verticesReduced.set(0);
  }

  public static void addVertices(int count) {
    totalVerticesQueued.addAndGet(count);
  }

  public static void addDrawCommand() { totalDrawCommands.incrementAndGet(); }

  public static void recordLodUsage(int lodLevel, int originalVertices,
                                    int actualVertices) {
    if (lodLevel == 0) {
      chunksAtLod0.incrementAndGet();
    } else if (lodLevel == 1) {
      chunksAtLod1.incrementAndGet();
    } else if (lodLevel == 2) {
      chunksAtLod2.incrementAndGet();
    }
    verticesReduced.addAndGet(originalVertices - actualVertices);
  }

  public static long getTotalVertices() { return totalVerticesQueued.get(); }

  public static long getTotalDrawCommands() { return totalDrawCommands.get(); }

  public static long getChunksAtLod0() { return chunksAtLod0.get(); }

  public static long getChunksAtLod1() { return chunksAtLod1.get(); }

  public static long getChunksAtLod2() { return chunksAtLod2.get(); }

  public static long getVerticesReduced() { return verticesReduced.get(); }

  public static double getAverageVerticesPerDraw() {
    long draws = getTotalDrawCommands();
    if (draws == 0)
      return 0.0;
    return (double)getTotalVertices() / draws;
  }

  public static String getMetricsString() {
    return String.format(
        "Verts: %,d | Draws: %,d | Avg: %.0f | L0: %d | L1: %d | L2: %d",
        getTotalVertices(), getTotalDrawCommands(), getAverageVerticesPerDraw(),
        getChunksAtLod0(), getChunksAtLod1(), getChunksAtLod2());
  }
}
