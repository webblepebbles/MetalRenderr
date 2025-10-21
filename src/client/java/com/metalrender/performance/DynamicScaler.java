package com.metalrender.performance;

import com.metalrender.config.MetalRenderConfig;
import com.metalrender.util.MetalLogger;

public final class DynamicScaler {
  private double emaMs = 0.0D;
  private int slowFrameStreak = 0;
  private int fastFrameStreak = 0;
  private boolean announcedEnable = false;
  private long lastAdjustmentTime = 0L;
  private static final long MIN_ADJUSTMENT_INTERVAL_MS = 500;

  public void onFrameEnd(double frameMs) {
    if (!(frameMs <= 0.0D)) {
      double target = Math.max(1.0D, MetalRenderConfig.dqTargetFrameMs());
      if (!MetalRenderConfig.dynamicQuality()) {
        this.handleAutoEnable(frameMs, target);
        return;
      }
      this.slowFrameStreak = 0;
      if (this.emaMs <= 0.0D) {
        this.emaMs = frameMs;
      } else {
        this.emaMs = this.emaMs * 0.9D + frameMs * 0.1D;
      }
      double ratio = this.emaMs / target;
      float currScale = MetalRenderConfig.resolutionScale();

      long currentTime = System.currentTimeMillis();
      if (currentTime - lastAdjustmentTime < MIN_ADJUSTMENT_INTERVAL_MS) {
        return;
      }

      float minScale = MetalRenderConfig.dqMinScale();
      float maxScale = MetalRenderConfig.dqMaxScale();
      float scaleStep = MetalRenderConfig.dqScaleStep();
      boolean scaleChanged = false;
      float scaleDrop;
      float newScale;

      if (ratio > 1.05D) {
        scaleDrop = scaleStep;
        if (ratio > 1.35D) {
          scaleDrop = scaleStep * 2.0F;
        }
        if (ratio > 1.75D) {
          scaleDrop *= 3.0F;
        }
        newScale = Math.max(minScale, currScale - scaleDrop);
        scaleChanged = Math.abs(newScale - currScale) > 0.001F;
        currScale = newScale;
        this.fastFrameStreak = 0;
      } else if (ratio < 0.9D) {
        scaleDrop = scaleStep;
        if (ratio < 0.6D) {
          scaleDrop = scaleStep * 2.0F;
        }
        newScale = Math.min(maxScale, currScale + scaleDrop);
        scaleChanged = Math.abs(newScale - currScale) > 0.001F;
        currScale = newScale;
        ++this.fastFrameStreak;
      } else {
        this.fastFrameStreak = Math.max(0, this.fastFrameStreak - 1);
      }
      if (scaleChanged) {
        MetalRenderConfig.setResolutionScale(currScale);
        lastAdjustmentTime = currentTime;
        MetalLogger.info("[DQ] Resolution scale adjusted to %.2f "
                             + "(ratio=%.2f, target=%.1fms)",
                         currScale, ratio, target);
      }
    }
  }

  private void handleAutoEnable(double frameMs, double target) {
    if (frameMs > target * 1.2D) {
      ++this.slowFrameStreak;
      if (this.slowFrameStreak >= 6) {
        MetalRenderConfig.setDynamicQuality(true);
        if (!this.announcedEnable) {
          MetalLogger.info("[DQ] Auto-enabled dynamic quality after "
                               + "sustained high frame times (%.1f ms avg)",
                           frameMs);
          this.announcedEnable = true;
        }
        this.slowFrameStreak = 0;
      }
    } else {
      this.slowFrameStreak = Math.max(0, this.slowFrameStreak - 1);
    }
  }
}
