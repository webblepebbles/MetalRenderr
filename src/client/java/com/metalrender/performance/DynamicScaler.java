package com.metalrender.performance;

import com.metalrender.config.MetalRenderConfig;
import net.minecraft.client.MinecraftClient;

/**
 * Adaptive dynamic quality scaling:
 * - Tracks frame time EMA and adjusts resolution scale and render distance toward target.
 * - Keeps changes small and bounded to avoid oscillations.
 */
public final class DynamicScaler {
    private double emaMs = 0.0;
    private float lastScale = 1.0f;
    private int lastDistance = -1;

    public void onFrameEnd(double frameMs) {
        if (!MetalRenderConfig.dynamicQuality())
            return;
        // EMA for stability
        if (emaMs <= 0.0)
            emaMs = frameMs;
        else
            emaMs = emaMs * 0.9 + frameMs * 0.1;

        double target = MetalRenderConfig.dqTargetFrameMs();
        double error = emaMs - target; // positive means too slow

        float currScale = MetalRenderConfig.resolutionScale();
        int currDist = MinecraftClient.getInstance().options.getViewDistance().getValue();

        // Adjust resolution scale first in small steps
        float step = MetalRenderConfig.dqScaleStep();
        if (error > 1.0) {
            // Too slow: decrease scale
            currScale = Math.max(MetalRenderConfig.dqMinScale(), currScale - step);
        } else if (error < -1.0) {
            // Faster than needed: increase scale
            currScale = Math.min(MetalRenderConfig.dqMaxScale(), currScale + step);
        }

        // If scale is already at min and still too slow, reduce render distance by 1
        if (emaMs > target * 1.15 && Math.abs(currScale - MetalRenderConfig.dqMinScale()) < 1e-4) {
            currDist = Math.max(2, currDist - 1);
        }
        // If faster than target and scale near max, consider increasing distance by 1
        if (emaMs < target * 0.85 && Math.abs(currScale - MetalRenderConfig.dqMaxScale()) < 1e-4) {
            currDist = Math.min(32, currDist + 1);
        }

        // Apply changes if any
        if (Math.abs(currScale - lastScale) > 1e-3) {
            MetalRenderConfig.setResolutionScale(currScale);
            lastScale = currScale;
        }
        if (lastDistance != currDist) {
            MinecraftClient.getInstance().options.getViewDistance().setValue(currDist);
            lastDistance = currDist;
        }
    }
}
