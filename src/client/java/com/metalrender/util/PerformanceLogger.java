package com.metalrender.util;

public final class PerformanceLogger {
    private long frameCount = 0;
    private long totalChunksProcessed = 0;
    private long totalChunksDrawn = 0;
    private long totalFrustumCulled = 0;
    private long totalOcclusionCulled = 0;
    private long frameStartTime = 0;
    private double avgFrameTime = 0;
    private double currentFPS = 0;

    private long lastLogTime = System.currentTimeMillis();
    private final long LOG_INTERVAL = 2000;

    public void startFrame() {
        frameStartTime = System.nanoTime();
    }

    public void endFrame(int chunksProcessed, int chunksDrawn, int frustumCulled, int occlusionCulled) {
        long frameEndTime = System.nanoTime();
        double frameTime = (frameEndTime - frameStartTime) / 1_000_000.0;

        frameCount++;
        totalChunksProcessed += chunksProcessed;
        totalChunksDrawn += chunksDrawn;
        totalFrustumCulled += frustumCulled;
        totalOcclusionCulled += occlusionCulled;

        avgFrameTime = avgFrameTime * 0.95 + frameTime * 0.05;
        currentFPS = 1000.0 / Math.max(avgFrameTime, 0.1);

        long currentTime = System.currentTimeMillis();
        if (currentTime - lastLogTime >= LOG_INTERVAL) {
            logPerformanceStats();
            lastLogTime = currentTime;
        }
    }

    private void logPerformanceStats() {
        double cullingEfficiency = totalChunksProcessed > 0
            ? (double) (totalFrustumCulled + totalOcclusionCulled) / totalChunksProcessed * 100
            : 0;

        MetalLogger.info(
            String.format("[PERF] FPS: %.1f | FrameTime: %.2fms | Chunks: P:%d D:%d | Culled: F:%d O:%d (%.1f%%)",
                currentFPS, avgFrameTime, totalChunksProcessed, totalChunksDrawn, totalFrustumCulled,
                totalOcclusionCulled, cullingEfficiency));

        if (currentFPS < 60) {
            MetalLogger.info("[PERF] Performance below 60 FPS - LOD system adjusting.");
        }

        resetCounters();
    }

    private void resetCounters() {
        totalChunksProcessed = 0;
        totalChunksDrawn = 0;
        totalFrustumCulled = 0;
        totalOcclusionCulled = 0;
    }

    public double getCurrentFPS() {
        return currentFPS;
    }

    public double getAvgFrameTime() {
        return avgFrameTime;
    }

    public long getFrameCount() {
        return frameCount;
    }
}