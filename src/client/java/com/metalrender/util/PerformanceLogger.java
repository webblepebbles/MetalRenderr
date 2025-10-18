package com.metalrender.util;

import com.metalrender.config.MetalRenderConfig;
import com.metalrender.performance.RenderingMetrics;
import net.minecraft.client.MinecraftClient;

public final class PerformanceLogger {
  private static long frameCount = 0L;
  private long totalChunksProcessed = 0L;
  private long totalChunksDrawn = 0L;
  private long totalFrustumCulled = 0L;
  private long totalOcclusionCulled = 0L;
  private long frameStartTime = 0L;
  private long lastFrameStartTime = 0L;
  private double lastFrameTime = 0.0D;
  private double avgFrameTime = 0.0D;
  private double currentFPS = 0.0D;
  private static long lastLogTime = System.currentTimeMillis();
  private static final long LOG_INTERVAL = 60000L;

  public void startFrame() {
    this.frameStartTime = System.nanoTime();
    if (this.lastFrameStartTime != 0L) {
      this.lastFrameTime =
          (double)(this.frameStartTime - this.lastFrameStartTime) / 1000000.0D;
    }

    this.lastFrameStartTime = this.frameStartTime;
  }

  public void endFrame(int chunksProcessed, int chunksDrawn, int frustumCulled,
                       int occlusionCulled) {
    long frameEndTime = System.nanoTime();
    double frameTime;
    if (this.lastFrameTime > 0.0D) {
      frameTime = this.lastFrameTime;
    } else {
      frameTime = (double)(frameEndTime - this.frameStartTime) / 1000000.0D;
    }

    ++PerformanceLogger.frameCount;
    this.totalChunksProcessed += (long)chunksProcessed;
    this.totalChunksDrawn += (long)chunksDrawn;
    this.totalFrustumCulled += (long)frustumCulled;
    this.totalOcclusionCulled += (long)occlusionCulled;
    this.avgFrameTime = this.avgFrameTime * 0.95D + frameTime * 0.05D;
    this.currentFPS = 1000.0D / Math.max(this.avgFrameTime, 0.1D);
    long currentTime = System.currentTimeMillis();
    if (currentTime - PerformanceLogger.lastLogTime >= LOG_INTERVAL) {
      this.logPerformanceStats();
      PerformanceLogger.lastLogTime = currentTime;
    }
  }

  private void logPerformanceStats() {
    double cullingEfficiency =
        this.totalChunksProcessed > 0L
            ? (double)(this.totalFrustumCulled + this.totalOcclusionCulled) /
                  (double)this.totalChunksProcessed * 100.0D
            : 0.0D;
    MetalLogger.info("[PERF] FPS: %.1f | FrameTime: %.2fms | Chunks: P:%d "
                         + "D:%d | Culled: F:%d O:%d (%.1f%%)",
                     this.currentFPS, this.avgFrameTime,
                     this.totalChunksProcessed, this.totalChunksDrawn,
                     this.totalFrustumCulled, this.totalOcclusionCulled,
                     cullingEfficiency);
    MetalLogger.info(
        "[PERF][DQ] Dynamic=%s | Target=%.1fms | Scale=%.2f | ViewDist=%d",
        MetalRenderConfig.dynamicQuality() ? "on" : "off",
        MetalRenderConfig.dqTargetFrameMs(),
        MetalRenderConfig.resolutionScale(),
        MinecraftClient.getInstance().options.getViewDistance().getValue());
    MetalLogger.info("[PERF][MESH] %s", RenderingMetrics.getMetricsString());
    if (this.currentFPS < 150.0D) {
      MetalLogger.info(
          "[PERF] Performance below 150 FPS - LOD system adjusting.");
    }

    this.resetCounters();
  }

  private void resetCounters() {
    this.totalChunksProcessed = 0L;
    this.totalChunksDrawn = 0L;
    this.totalFrustumCulled = 0L;
    this.totalOcclusionCulled = 0L;
  }

  public double getCurrentFPS() { return this.currentFPS; }

  public double getAvgFrameTime() { return this.avgFrameTime; }

  public double getLastFrameTime() {
    return this.lastFrameTime > 0.0D ? this.lastFrameTime : this.avgFrameTime;
  }

  public long getFrameCount() { return PerformanceLogger.frameCount; }
}
