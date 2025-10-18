package com.metalrender.temporal;

import com.metalrender.config.MetalRenderConfig;

/**
 * Maintains a soft target resolution scale when temporal anti-aliasing is
 * enabled. The upscaler nudges the current render scale towards the configured
 * temporal target every frame, allowing the dynamic quality controller to keep
 * the frame rate stable without fighting the temporal resolve.
 */
public final class TemporalUpscaler {
  private static final float ADJUST_SPEED = 0.08F;

  public void updateScale() {
    if (!MetalRenderConfig.temporalAAEnabled()) {
      return;
    }

    float target = MetalRenderConfig.temporalUpscaleTarget();
    float current = MetalRenderConfig.resolutionScale();
    float delta = target - current;
    if (Math.abs(delta) < 0.0005F) {
      return;
    }

    float adjustment = delta * ADJUST_SPEED;
    MetalRenderConfig.setResolutionScale(current + adjustment);
  }
}
