package com.metalrender.util;

import net.minecraft.client.render.Camera;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

public final class LODManager {
    private static final int BASE_RENDER_DISTANCE = 32;
    private static final int MIN_RENDER_DISTANCE = 8;
    private static final int MAX_RENDER_DISTANCE = 128;

    private static final double TARGET_FPS = 200.0;
    private static final double FPS_ADJUSTMENT_THRESHOLD = 10.0;

    private int currentRenderDistance = BASE_RENDER_DISTANCE;
    private double averageFPS = TARGET_FPS;
    private long lastAdjustmentTime = 0;
    private static final long ADJUSTMENT_COOLDOWN = 2000;

    private final double[] fpsHistory = new double[10];
    private int fpsHistoryIndex = 0;
    private boolean fpsHistoryFull = false;

    public void updatePerformanceMetrics(double currentFPS, long frameTime) {
        fpsHistory[fpsHistoryIndex] = currentFPS;
        fpsHistoryIndex = (fpsHistoryIndex + 1) % fpsHistory.length;
        if (!fpsHistoryFull && fpsHistoryIndex == 0) {
            fpsHistoryFull = true;
        }

        double sum = 0;
        int count = fpsHistoryFull ? fpsHistory.length : fpsHistoryIndex;
        for (int i = 0; i < count; i++) {
            sum += fpsHistory[i];
        }
        averageFPS = sum / count;

        long currentTime = System.currentTimeMillis();
        if (currentTime - lastAdjustmentTime > ADJUSTMENT_COOLDOWN) {
            adjustRenderDistance();
            lastAdjustmentTime = currentTime;
        }
    }

    private void adjustRenderDistance() {
        if (averageFPS < TARGET_FPS - FPS_ADJUSTMENT_THRESHOLD && currentRenderDistance > MIN_RENDER_DISTANCE) {
            currentRenderDistance = Math.max(MIN_RENDER_DISTANCE, currentRenderDistance - 2);
            MetalLogger.info("[LOD] Reduced render distance to " + currentRenderDistance
                + " (FPS: " + String.format("%.1f", averageFPS) + ")");
        } else if (averageFPS > TARGET_FPS + FPS_ADJUSTMENT_THRESHOLD && currentRenderDistance < MAX_RENDER_DISTANCE) {
            currentRenderDistance = Math.min(MAX_RENDER_DISTANCE, currentRenderDistance + 1);
            MetalLogger.info("[LOD] Increased render distance to " + currentRenderDistance
                + " (FPS: " + String.format("%.1f", averageFPS) + ")");
        }
    }

    public int getLODLevel(BlockPos chunkPos, Camera camera) {
        if (camera == null)
            return 0;

        Vec3d cameraPos = camera.getPos();
        double distance = Math.sqrt(
            Math.pow((chunkPos.getX() + 8) - cameraPos.x, 2) + Math.pow((chunkPos.getZ() + 8) - cameraPos.z, 2));

        double chunkDistance = distance / 16.0;
        // detail adjustment
        if (chunkDistance <= currentRenderDistance * 0.3) {
            return 0;
        } else if (chunkDistance <= currentRenderDistance * 0.6) {
            return 1;
        } else if (chunkDistance <= currentRenderDistance * 0.9) {
            return 2;
        } else {
            return 3;
        }
    }

    public boolean shouldRenderChunk(BlockPos chunkPos, Camera camera) {
        if (camera == null)
            return true;

        Vec3d cameraPos = camera.getPos();
        double distance = Math.sqrt(
            Math.pow((chunkPos.getX() + 8) - cameraPos.x, 2) + Math.pow((chunkPos.getZ() + 8) - cameraPos.z, 2));

        return distance <= currentRenderDistance * 16.0;
    }

    public int getCurrentRenderDistance() {
        return currentRenderDistance;
    }

    public double getAverageFPS() {
        return averageFPS;
    }

    public void setRenderDistance(int distance) {
        currentRenderDistance = Math.max(MIN_RENDER_DISTANCE, Math.min(MAX_RENDER_DISTANCE, distance));
    }
}