package com.metalrender.lod;

import com.metalrender.config.MetalRenderConfig;
import com.metalrender.util.FrustumCuller;
import org.joml.Matrix4f;

public final class ExtendedLodCuller {

    private final FrustumCuller frustumCuller = new FrustumCuller();

    private static final int MIN_DRAW_BUDGET = 256;
    private static final int MAX_DRAW_BUDGET = 65536;
    private static final float TARGET_FRAME_MS = 12.0f;
    private int currentBudget = MAX_DRAW_BUDGET;
    private int drawsThisFrame = 0;

    private final long[] frameTimes = new long[30];
    private int frameTimeIndex = 0;
    private boolean frameTimesFull = false;
    private float smoothedFrameMs = 8.0f;

    private float camX, camY, camZ;
    private float camPitch;
    private int cameraAltitudeAboveGround = 64;
    private float effectiveHorizonDist;

    private int frustumCulled;
    private int yCulled;
    private int distanceCulled;
    private int horizonCulled;
    private int budgetCulled;
    private int totalTested;
    private int totalPassed;

    public ExtendedLodCuller() {
    }

    public void beginFrame(Matrix4f viewProjection, float camX, float camY, float camZ,
            float cameraPitch, int altitudeAboveGround) {
        this.frustumCuller.update(viewProjection);
        this.camX = camX;
        this.camY = camY;
        this.camZ = camZ;
        this.camPitch = cameraPitch;
        this.cameraAltitudeAboveGround = Math.max(0, altitudeAboveGround);

        float maxDistBlocks = MetalRenderConfig.extremeRenderDistance() * 16.0f;

        if (cameraAltitudeAboveGround < 16) {
            float altFactor = 0.3f + 0.3f * (cameraAltitudeAboveGround / 16.0f);
            effectiveHorizonDist = maxDistBlocks * altFactor;
        } else if (cameraAltitudeAboveGround < 64) {
            float altFactor = 0.6f + 0.4f * ((cameraAltitudeAboveGround - 16) / 48.0f);
            effectiveHorizonDist = maxDistBlocks * altFactor;
        } else {
            effectiveHorizonDist = maxDistBlocks;
        }

        frustumCulled = 0;
        yCulled = 0;
        distanceCulled = 0;
        horizonCulled = 0;
        budgetCulled = 0;
        totalTested = 0;
        totalPassed = 0;
        drawsThisFrame = 0;
    }

    public void recordFrameTime(long frameTimeNs) {
        frameTimes[frameTimeIndex] = frameTimeNs;
        frameTimeIndex = (frameTimeIndex + 1) % frameTimes.length;
        if (frameTimeIndex == 0)
            frameTimesFull = true;

        int count = frameTimesFull ? frameTimes.length : frameTimeIndex;
        if (count > 0) {
            long total = 0;
            for (int i = 0; i < count; i++)
                total += frameTimes[i];
            smoothedFrameMs = (total / (float) count) / 1_000_000.0f;
        }

        if (smoothedFrameMs > TARGET_FRAME_MS * 1.5f) {
            currentBudget = Math.max(MIN_DRAW_BUDGET, (int) (currentBudget * 0.5f));
        } else if (smoothedFrameMs > TARGET_FRAME_MS * 1.3f) {
            currentBudget = Math.max(MIN_DRAW_BUDGET, (int) (currentBudget * 0.7f));
        } else if (smoothedFrameMs > TARGET_FRAME_MS) {
            currentBudget = Math.max(MIN_DRAW_BUDGET, currentBudget - 128);
        } else if (smoothedFrameMs < TARGET_FRAME_MS * 0.7f) {
            currentBudget = Math.min(MAX_DRAW_BUDGET, (int) (currentBudget * 1.2f));
        } else if (smoothedFrameMs < TARGET_FRAME_MS * 0.9f) {
            currentBudget = Math.min(MAX_DRAW_BUDGET, currentBudget + 64);
        }
    }

    public boolean shouldRender(int sectionX, int sectionY, int sectionZ, float boundsRadius) {
        totalTested++;

        float worldX = sectionX * 16.0f;
        float worldY = sectionY * 16.0f;
        float worldZ = sectionZ * 16.0f;

        float sectionCenterY = worldY + 8.0f;
        float yDiff = Math.abs(sectionCenterY - camY);
        if (yDiff > 320.0f) {
            yCulled++;
            return false;
        }

        float dx = worldX + 8.0f - camX;
        float dz = worldZ + 8.0f - camZ;
        float horizDistSq = dx * dx + dz * dz;
        float maxDist = MetalRenderConfig.extremeRenderDistance() * 16.0f;
        if (horizDistSq > maxDist * maxDist) {
            distanceCulled++;
            return false;
        }

        if (horizDistSq > effectiveHorizonDist * effectiveHorizonDist) {
            if (sectionCenterY <= camY + 32.0f) {
                horizonCulled++;
                return false;
            }
        }

        if (frustumCuller.isValid()) {
            float relX = worldX - camX;
            float relY = worldY - camY;
            float relZ = worldZ - camZ;
            float pad = 2.0f;
            if (!frustumCuller.aabbIntersectsFrustum(
                    relX - pad, relY - pad, relZ - pad,
                    relX + 16.0f + pad, relY + 16.0f + pad, relZ + 16.0f + pad)) {
                frustumCulled++;
                return false;
            }
        }

        if (drawsThisFrame >= currentBudget) {
            budgetCulled++;
            return false;
        }

        drawsThisFrame++;
        totalPassed++;
        return true;
    }

    public int getFrustumCulled() {
        return frustumCulled;
    }

    public int getYCulled() {
        return yCulled;
    }

    public int getDistanceCulled() {
        return distanceCulled;
    }

    public int getHorizonCulled() {
        return horizonCulled;
    }

    public int getBudgetCulled() {
        return budgetCulled;
    }

    public int getTotalTested() {
        return totalTested;
    }

    public int getTotalPassed() {
        return totalPassed;
    }

    public int getCurrentBudget() {
        return currentBudget;
    }

    public float getSmoothedFrameMs() {
        return smoothedFrameMs;
    }

    public float getEffectiveHorizonDist() {
        return effectiveHorizonDist;
    }

    public void extractFrustumPlanes(float[] out) {
        if (out == null || out.length < 24)
            return;
        frustumCuller.getPlanes(out);
    }
}
