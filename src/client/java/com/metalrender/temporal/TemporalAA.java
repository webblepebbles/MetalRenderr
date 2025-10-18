package com.metalrender.temporal;

import com.metalrender.config.MetalRenderConfig;
import java.util.concurrent.atomic.AtomicInteger;
import org.joml.Matrix4f;

/**
 * Simple temporal anti-aliasing helper that generates Halton sequence jitter
 * offsets and exposes a blend factor used during the resolve pass. The actual
 * history resolve is performed on the native side, but the jitter needs to be
 * baked into the projection matrix before chunk visibility tests.
 */
public final class TemporalAA {
  private static final int SAMPLE_COUNT = 8;
  private static final float HALTON_DENOM_BASE2 = 2.0F;
  private static final float HALTON_DENOM_BASE3 = 3.0F;

  private final AtomicInteger frameIndex = new AtomicInteger();
  private float jitterX;
  private float jitterY;
  private float blendFactor;
  private boolean enabled;

  public void beginFrame(int viewportWidth, int viewportHeight) {
    this.enabled = MetalRenderConfig.temporalAAEnabled();
    if (!this.enabled || viewportWidth <= 0 || viewportHeight <= 0) {
      this.jitterX = 0.0F;
      this.jitterY = 0.0F;
      this.blendFactor = 0.0F;
      return;
    }

    int sampleIndex =
        Math.floorMod(this.frameIndex.incrementAndGet(), SAMPLE_COUNT);
    float rawJitterX = halton(sampleIndex + 1, HALTON_DENOM_BASE2) - 0.5F;
    float rawJitterY = halton(sampleIndex + 1, HALTON_DENOM_BASE3) - 0.5F;
    this.jitterX = rawJitterX / Math.max(1.0F, viewportWidth);
    this.jitterY = rawJitterY / Math.max(1.0F, viewportHeight);
    this.blendFactor = MetalRenderConfig.temporalBlendFactor();
  }

  public void applyJitter(Matrix4f projection) {
    if (!this.enabled || projection == null) {
      return;
    }

    float jitterScale = 2.0F;
    projection.m20(projection.m20() + this.jitterX * jitterScale);
    projection.m21(projection.m21() + this.jitterY * jitterScale);
  }

  public float jitterX() { return this.jitterX; }

  public float jitterY() { return this.jitterY; }

  public float blendFactor() { return this.blendFactor; }

  public boolean isEnabled() { return this.enabled; }

  private static float halton(int index, float base) {
    float result = 0.0F;
    float f = 1.0F / base;
    int i = index;
    while (i > 0) {
      result += f * (i % (int)base);
      i = (int)Math.floor(i / base);
      f /= base;
    }
    return result;
  }
}
