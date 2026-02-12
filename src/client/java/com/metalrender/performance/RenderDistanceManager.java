package com.metalrender.performance;

import com.metalrender.config.MetalRenderConfig;
import com.metalrender.util.MetalLogger;
import net.minecraft.client.MinecraftClient;

public final class RenderDistanceManager {
    private static final RenderDistanceManager INSTANCE = new RenderDistanceManager();
    private static final int SAMPLE_WINDOW = 60;
    private final long[] frameTimes = new long[SAMPLE_WINDOW];
    private int frameIndex = 0;
    private boolean windowFull = false;
    private int currentRenderDistance = -1;
    private int userMaxRenderDistance = 16;
    private int minRenderDistance = 4;
    private float targetFps = 60.0f;
    private float lowerThreshold = 0.85f;
    private float upperThreshold = 1.15f;
    private long lastAdjustTime = 0;
    private static final long ADJUST_COOLDOWN_MS = 2000;
    private long lastLogTime = 0;
    private float smoothedFps = 0.0f;
    private boolean enabled = false;

    private RenderDistanceManager() {
    }

    public static RenderDistanceManager getInstance() {
        return INSTANCE;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (enabled && this.currentRenderDistance < 0) {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc != null && mc.options != null) {
                this.currentRenderDistance = mc.options.getViewDistance().getValue();
                this.userMaxRenderDistance = this.currentRenderDistance;
            }
        }
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    public void setTargetFps(float fps) {
        this.targetFps = Math.max(30.0f, fps);
    }

    public float getTargetFps() {
        return this.targetFps;
    }

    public int getCurrentRenderDistance() {
        return this.currentRenderDistance;
    }

    public void updateRenderDistance() {
        if (!this.enabled) {
            return;
        }

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.options == null) {
            return;
        }
        if (this.currentRenderDistance < 0) {
            this.currentRenderDistance = mc.options.getViewDistance().getValue();
            this.userMaxRenderDistance = this.currentRenderDistance;
        }
        long now = System.nanoTime();
        this.frameTimes[this.frameIndex] = now;
        this.frameIndex = (this.frameIndex + 1) % SAMPLE_WINDOW;
        if (this.frameIndex == 0) {
            this.windowFull = true;
        }
        if (!this.windowFull) {
            return;
        }
        int oldestIndex = this.frameIndex;
        long oldestTime = this.frameTimes[oldestIndex];
        long newestTime = this.frameTimes[(this.frameIndex + SAMPLE_WINDOW - 1) % SAMPLE_WINDOW];
        long elapsed = newestTime - oldestTime;
        if (elapsed <= 0) {
            return;
        }

        float averageFps = (float) (SAMPLE_WINDOW - 1) * 1_000_000_000.0f / (float) elapsed;
        if (this.smoothedFps <= 0.0f) {
            this.smoothedFps = averageFps;
        } else {
            this.smoothedFps = this.smoothedFps * 0.8f + averageFps * 0.2f;
        }
        long currentMs = now / 1_000_000L;
        if (currentMs - this.lastAdjustTime < ADJUST_COOLDOWN_MS) {
            return;
        }
        float lowerBound = this.targetFps * this.lowerThreshold;
        float upperBound = this.targetFps * this.upperThreshold;

        int newDistance = this.currentRenderDistance;

        if (this.smoothedFps < lowerBound && this.currentRenderDistance > this.minRenderDistance) {
            newDistance = this.currentRenderDistance - 1;
        } else if (this.smoothedFps > upperBound && this.currentRenderDistance < this.userMaxRenderDistance) {
            newDistance = this.currentRenderDistance + 1;
        }

        if (newDistance != this.currentRenderDistance) {
            newDistance = Math.max(this.minRenderDistance, Math.min(this.userMaxRenderDistance, newDistance));
            this.currentRenderDistance = newDistance;
            mc.options.getViewDistance().setValue(newDistance);
            this.lastAdjustTime = currentMs;

            if (currentMs - this.lastLogTime > 5000) {
                MetalLogger.info("[RenderDistance] Adjusted to %d chunks (FPS: %.1f, target: %.1f)",
                        newDistance, this.smoothedFps, this.targetFps);
                this.lastLogTime = currentMs;
            }
        }
    }

    public float getSmoothedFps() {
        return this.smoothedFps;
    }

    public void reset() {
        this.currentRenderDistance = -1;
        this.windowFull = false;
        this.frameIndex = 0;
        this.smoothedFps = 0.0f;
    }
}
