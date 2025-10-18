package com.metalrender.render;

import com.metalrender.util.MetalLogger;

public class PipelineCache {
  private final long handle;
  private boolean warmedUp = false;

  private PipelineCache(long handle) { this.handle = handle; }

  public static PipelineCache create(long handle) {
    if (handle == 0L) {
      MetalLogger.warn("[PipelineCache] Cannot create cache with null handle");
      return null;
    }
    return new PipelineCache(handle);
  }

  public void prewarm() {
    if (!warmedUp) {
      warmedUp = true;
      MetalLogger.debug("[PipelineCache] Pipeline cache prewarmed");
    }
  }

  public void reset() {
    warmedUp = false;
    MetalLogger.debug("[PipelineCache] Pipeline cache reset");
  }
}
