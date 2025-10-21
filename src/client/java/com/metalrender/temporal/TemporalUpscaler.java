package com.metalrender.temporal;

import com.metalrender.config.MetalRenderConfig;

public final class TemporalUpscaler {
  private static final float ADJUST_SPEED = 0.1F;

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
