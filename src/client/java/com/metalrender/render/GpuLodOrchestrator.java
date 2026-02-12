package com.metalrender.render;

import com.metalrender.config.MetalRenderConfig;
import com.metalrender.nativebridge.NativeBridge;
import com.metalrender.util.MetalLogger;

public class GpuLodOrchestrator {

    private static final int MAX_LOD_LEVELS = 6;
    private static final int MAX_CHUNKS_PER_LOD = 65536;

    private final long deviceHandle;
    private boolean initialized = false;
    private boolean multiICBReady = false;

    private final int[] lodDrawCounts = new int[MAX_LOD_LEVELS];
    private int totalDrawsLastFrame = 0;
    private int framesRendered = 0;

    private final float[] lodThresholds = new float[MAX_LOD_LEVELS];

    public GpuLodOrchestrator(long deviceHandle) {
        this.deviceHandle = deviceHandle;
    }

    public boolean initialize() {
        if (initialized)
            return true;
        if (deviceHandle == 0L)
            return false;

        try {

            multiICBReady = NativeBridge.nInitMultiICB(deviceHandle, MAX_CHUNKS_PER_LOD);
            if (!multiICBReady) {
                MetalLogger.warn("[GpuLodOrchestrator] Multi-ICB init failed, falling back to single ICB");
                return false;
            }

            for (int lod = 0; lod <= 2; lod++) {
                NativeBridge.nSetLodFunctionConstants(deviceHandle, lod,
                        true,
                        true,
                        true);
            }

            for (int lod = 3; lod <= 4; lod++) {
                NativeBridge.nSetLodFunctionConstants(deviceHandle, lod,
                        false,
                        true,
                        true);
            }

            NativeBridge.nSetLodFunctionConstants(deviceHandle, 5,
                    false,
                    false,
                    true);

            updateLodThresholds();

            initialized = true;
            MetalLogger.info("[GpuLodOrchestrator] Initialized with {} LOD levels, multi-ICB={}",
                    MAX_LOD_LEVELS, multiICBReady);
            return true;

        } catch (Throwable e) {
            MetalLogger.error("[GpuLodOrchestrator] Init failed: {}", e.getMessage());
            return false;
        }
    }

    public void updateLodThresholds() {
        lodThresholds[0] = MetalRenderConfig.lodLevel0Distance();
        lodThresholds[1] = MetalRenderConfig.lodLevel1Distance();
        lodThresholds[2] = MetalRenderConfig.lodLevel2Distance();
        lodThresholds[3] = MetalRenderConfig.lodLevel3Distance();
        lodThresholds[4] = MetalRenderConfig.lodLevel4Distance();
        lodThresholds[5] = MetalRenderConfig.lodLevel5Distance();

        if (initialized && deviceHandle != 0L) {
            NativeBridge.nSetLodThresholds(deviceHandle, lodThresholds);
        }
    }

    public int renderFrame(float[] cameraPos, float[] frustumPlanes, int totalChunks) {
        if (!initialized || !multiICBReady || deviceHandle == 0L) {
            return 0;
        }

        try {

            if (MetalRenderConfig.gpuLodSelectionEnabled()) {
                NativeBridge.nDispatchGpuLodSelect(deviceHandle, cameraPos,
                        frustumPlanes, totalChunks);
            }

            totalDrawsLastFrame = NativeBridge.nExecuteMultiICB(deviceHandle);

            int[] counts = NativeBridge.nGetLodDrawCounts(deviceHandle);
            if (counts != null && counts.length == MAX_LOD_LEVELS) {
                System.arraycopy(counts, 0, lodDrawCounts, 0, MAX_LOD_LEVELS);
            }

            framesRendered++;
            if (framesRendered < 5 || framesRendered % 600 == 0) {
                MetalLogger.info("[GpuLodOrchestrator] Frame {}: {} total draws " +
                        "[LOD0={} LOD1={} LOD2={} LOD3={} LOD4={} LOD5={}]",
                        framesRendered, totalDrawsLastFrame,
                        lodDrawCounts[0], lodDrawCounts[1], lodDrawCounts[2],
                        lodDrawCounts[3], lodDrawCounts[4], lodDrawCounts[5]);
            }

            return totalDrawsLastFrame;

        } catch (Throwable e) {
            MetalLogger.error("[GpuLodOrchestrator] renderFrame failed: {}", e.getMessage());
            return 0;
        }
    }

    public boolean shouldRenderEntities(float distanceBlocks) {
        float distanceChunks = distanceBlocks / 16.0f;
        return distanceChunks <= MetalRenderConfig.entityRenderDistanceChunks();
    }

    public int getLodLevel(float distanceBlocks) {
        for (int i = 0; i < MAX_LOD_LEVELS - 1; i++) {
            if (distanceBlocks <= lodThresholds[i]) {
                return i;
            }
        }
        return MAX_LOD_LEVELS - 1;
    }

    public int getTotalDrawsLastFrame() {
        return totalDrawsLastFrame;
    }

    public int getLodDrawCount(int lodLevel) {
        if (lodLevel < 0 || lodLevel >= MAX_LOD_LEVELS)
            return 0;
        return lodDrawCounts[lodLevel];
    }

    public int[] getAllLodDrawCounts() {
        return lodDrawCounts.clone();
    }

    public boolean isInitialized() {
        return initialized;
    }

    public void destroy() {
        if (deviceHandle != 0L && multiICBReady) {
            NativeBridge.nDestroyMultiICB(deviceHandle);
        }
        multiICBReady = false;
        initialized = false;
    }
}
