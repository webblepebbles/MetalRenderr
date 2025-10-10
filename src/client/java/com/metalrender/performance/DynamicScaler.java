package com.metalrender.performance;

import com.metalrender.config.MetalRenderConfig;
import com.metalrender.util.MetalLogger;
import net.minecraft.client.MinecraftClient;

/**
 * Adaptive dynamic quality scaling targeting a configurable frame budget.
 */
public final class DynamicScaler {
    private double emaMs = 0.0;
    private int lastDistance = -1;
    private int slowFrameStreak = 0;
    private int fastFrameStreak = 0;
    private boolean announcedEnable = false;

    public void onFrameEnd(double frameMs) {
        if (frameMs <= 0.0)
            return;

        double target = Math.max(1.0, MetalRenderConfig.dqTargetFrameMs());

        if (!MetalRenderConfig.dynamicQuality()) {
            handleAutoEnable(frameMs, target);
            return;
        }

        slowFrameStreak = 0;

        // Exponential moving average for stability.
        if (emaMs <= 0.0)
            emaMs = frameMs;
        else
            emaMs = emaMs * 0.8 + frameMs * 0.2;

        double ratio = emaMs / target;

        MinecraftClient client = MinecraftClient.getInstance();
        float currScale = MetalRenderConfig.resolutionScale();
        int currDist = client.options.getViewDistance().getValue();
        if (lastDistance < 0)
            lastDistance = currDist;

        float minScale = MetalRenderConfig.dqMinScale();
        float maxScale = MetalRenderConfig.dqMaxScale();
        float scaleStep = MetalRenderConfig.dqScaleStep();

        boolean scaleChanged = false;
        if (ratio > 1.05) {
            float scaleDrop = scaleStep;
            if (ratio > 1.35)
                scaleDrop *= 2f;
            if (ratio > 1.75)
                scaleDrop *= 3f;
            float newScale = Math.max(minScale, currScale - scaleDrop);
            scaleChanged = Math.abs(newScale - currScale) > 1e-3f;
            currScale = newScale;
            fastFrameStreak = 0;
        } else if (ratio < 0.9) {
            float scaleRaise = scaleStep;
            if (ratio < 0.6)
                scaleRaise *= 2f;
            float newScale = Math.min(maxScale, currScale + scaleRaise);
            scaleChanged = Math.abs(newScale - currScale) > 1e-3f;
            currScale = newScale;
            fastFrameStreak++;
        } else {
            fastFrameStreak = Math.max(0, fastFrameStreak - 1);
        }

        int minDistance = Math.max(2, MetalRenderConfig.dqMinViewDistance());
        int maxDistance = Math.max(minDistance, MetalRenderConfig.dqMaxViewDistance());
        int distanceStep = Math.max(1, MetalRenderConfig.dqViewDistanceStep());

        boolean distanceChanged = false;
        if (ratio > 1.15 && currScale <= minScale + 1e-4f) {
            int steps = ratio > 2.25 ? 3 : (ratio > 1.75 ? 2 : 1);
            int newDist = Math.max(minDistance, currDist - steps * distanceStep);
            distanceChanged = newDist != currDist;
            currDist = newDist;
        } else if (ratio < 0.75 && currScale >= maxScale - 1e-4f) {
            if (fastFrameStreak > 4) {
                int newDist = Math.min(maxDistance, currDist + distanceStep);
                distanceChanged = newDist != currDist;
                currDist = newDist;
                fastFrameStreak = 0;
            }
        } else if (currDist > lastDistance && ratio > 0.95) {
            // Prevent premature recovery while still near the budget.
            currDist = lastDistance;
        }

        if (scaleChanged) {
            MetalRenderConfig.setResolutionScale(currScale);
            MetalLogger.debug("[DQ] Adjusted resolution scale to %.2f (ratio=%.2f)", currScale, ratio);
        }

        if (distanceChanged && currDist != lastDistance) {
            client.options.getViewDistance().setValue(currDist);
            lastDistance = currDist;
            MetalLogger.debug("[DQ] Adjusted view distance to %d (ratio=%.2f, scale=%.2f)", currDist, ratio, currScale);
        }
    }

    private void handleAutoEnable(double frameMs, double target) {
        if (frameMs > target * 1.2) {
            slowFrameStreak++;
            if (slowFrameStreak >= 6) {
                MetalRenderConfig.setDynamicQuality(true);
                if (!announcedEnable) {
                    MetalLogger.info("[DQ] Enabled dynamic quality after sustained %.1f ms frames", frameMs);
                    announcedEnable = true;
                }
                slowFrameStreak = 0;
            }
        } else {
            slowFrameStreak = Math.max(0, slowFrameStreak - 1);
        }
    }
}
