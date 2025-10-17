package com.metalrender.render;

import com.metalrender.util.MetalLogger;

/**
 * Manages pipeline state caching for MetalRender.
 * This is a lightweight wrapper that can be extended in the future
 * to provide actual pipeline prewarming and caching functionality.
 */
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
      // Pipeline prewarming logic can be added here in the future
      // For now, this is a no-op that allows the renderer to initialize
      warmedUp = true;
      MetalLogger.debug("[PipelineCache] Pipeline cache prewarmed");
    }
  }

  public void reset() {
    warmedUp = false;
    MetalLogger.debug("[PipelineCache] Pipeline cache reset");
  }
}
