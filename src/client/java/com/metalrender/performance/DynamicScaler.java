package com.metalrender.performance;

import com.metalrender.config.MetalRenderConfig;
import com.metalrender.temporal.TemporalUpscaler;
import com.metalrender.util.MetalLogger;
import net.minecraft.client.MinecraftClient;

public final class DynamicScaler {
  private double emaMs = 0.0D;
  private int lastDistance = -1;
  private int slowFrameStreak = 0;
  private int fastFrameStreak = 0;
  private boolean announcedEnable = false;
  private final TemporalUpscaler temporalUpscaler = new TemporalUpscaler();

  private int frameCounter = 0;
  private static final int UPDATE_FREQUENCY = 3;

  public void onFrameEnd(double frameMs) {
    if (!(frameMs <= 0.0D)) {
      double target = Math.max(1.0D, MetalRenderConfig.dqTargetFrameMs());
      if (!MetalRenderConfig.dynamicQuality()) {
        this.handleAutoEnable(frameMs, target);
      } else {
        this.slowFrameStreak = 0;
        if (this.emaMs <= 0.0D) {
          this.emaMs = frameMs;
        } else {
          this.emaMs = this.emaMs * 0.8D + frameMs * 0.2D;
        }

        double ratio = this.emaMs / target;
        MinecraftClient client = MinecraftClient.getInstance();
        float currScale = MetalRenderConfig.resolutionScale();
        int currDist = (Integer)client.options.getViewDistance().getValue();
        if (this.lastDistance < 0) {
          this.lastDistance = currDist;
        }

        float minScale = MetalRenderConfig.dqMinScale();
        float maxScale =
            MetalRenderConfig.temporalAAEnabled()
                ? Math.min(MetalRenderConfig.temporalUpscaleTarget(),
                           MetalRenderConfig.dqMaxScale())
                : MetalRenderConfig.dqMaxScale();
        float scaleStep = MetalRenderConfig.dqScaleStep();
        boolean scaleChanged = false;
        float scaleDrop;
        float newScale;
        frameCounter++;
        boolean shouldApplyChange = (frameCounter % UPDATE_FREQUENCY == 0);

        if (shouldApplyChange) {
          if (ratio > 1.15D) {
            scaleDrop = scaleStep;
            if (ratio > 1.35D) {
              scaleDrop = scaleStep * 2.0F;
            }

            if (ratio > 1.75D) {
              scaleDrop *= 3.0F;
            }

            newScale = Math.max(minScale, currScale - scaleDrop);
            scaleChanged = Math.abs(newScale - currScale) > 0.001F;
            if (scaleChanged) {
              currScale = newScale;
            }
            this.fastFrameStreak = 0;
          } else if (ratio < 0.80D) {
            scaleDrop = scaleStep;
            if (ratio < 0.6D) {
              scaleDrop = scaleStep * 2.0F;
            }

            newScale = Math.min(maxScale, currScale + scaleDrop);
            scaleChanged = Math.abs(newScale - currScale) > 0.001F;
            if (scaleChanged) {
              currScale = newScale;
            }
            ++this.fastFrameStreak;
          } else {
            this.fastFrameStreak = Math.max(0, this.fastFrameStreak - 1);
          }
          if (scaleChanged) {
            MetalRenderConfig.setResolutionScale(currScale);
            MetalLogger.debug(
                "[DQ] Adjusted resolution scale to %.2f (ratio=%.2f)",
                currScale, ratio);
          }
        }
      }

      this.temporalUpscaler.updateScale();
    }
  }

  private void handleAutoEnable(double frameMs, double target) {
    if (frameMs > target * 1.2D) {
      ++this.slowFrameStreak;
      if (this.slowFrameStreak >= 6) {
        MetalRenderConfig.setDynamicQuality(true);
        if (!this.announcedEnable) {
          MetalLogger.info(
              "[DQ] Enabled dynamic quality after sustained %.1f ms frames",
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
