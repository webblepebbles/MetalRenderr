package com.metalrender.performance;

import com.metalrender.config.MetalRenderConfig;
import com.metalrender.util.MetalLogger;
import net.minecraft.client.MinecraftClient;

public final class DynamicScaler {
   private double emaMs = 0.0D;
   private int lastDistance = -1;
   private int slowFrameStreak = 0;
   private int fastFrameStreak = 0;
   private boolean announcedEnable = false;

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

            int minDistance = Math.max(2, MetalRenderConfig.dqMinViewDistance());
            int maxDistance = Math.max(minDistance, MetalRenderConfig.dqMaxViewDistance());
            int distanceStep = Math.max(1, MetalRenderConfig.dqViewDistanceStep());
            boolean distanceChanged = false;
            int newDist;
            if (ratio > 1.15D && currScale <= minScale + 1.0E-4F) {
               newDist = ratio > 2.25D ? 3 : (ratio > 1.75D ? 2 : 1);
               int newDist = Math.max(minDistance, currDist - newDist * distanceStep);
               distanceChanged = newDist != currDist;
               currDist = newDist;
            } else if (ratio < 0.75D && currScale >= maxScale - 1.0E-4F) {
               if (this.fastFrameStreak > 4) {
                  newDist = Math.min(maxDistance, currDist + distanceStep);
                  distanceChanged = newDist != currDist;
                  currDist = newDist;
                  this.fastFrameStreak = 0;
               }
            } else if (currDist > this.lastDistance && ratio > 0.95D) {
               currDist = this.lastDistance;
            }

            if (scaleChanged) {
               MetalRenderConfig.setResolutionScale(currScale);
               MetalLogger.debug("[DQ] Adjusted resolution scale to %.2f (ratio=%.2f)", currScale, ratio);
            }

            if (distanceChanged && currDist != this.lastDistance) {
               client.options.getViewDistance().setValue(currDist);
               this.lastDistance = currDist;
               MetalLogger.debug("[DQ] Adjusted view distance to %d (ratio=%.2f, scale=%.2f)", currDist, ratio, currScale);
            }

         }
      }
   }

   private void handleAutoEnable(double frameMs, double target) {
      if (frameMs > target * 1.2D) {
         ++this.slowFrameStreak;
         if (this.slowFrameStreak >= 6) {
            MetalRenderConfig.setDynamicQuality(true);
            if (!this.announcedEnable) {
               MetalLogger.info("[DQ] Enabled dynamic quality after sustained %.1f ms frames", frameMs);
               this.announcedEnable = true;
            }

            this.slowFrameStreak = 0;
         }
      } else {
         this.slowFrameStreak = Math.max(0, this.slowFrameStreak - 1);
      }

   }
}
