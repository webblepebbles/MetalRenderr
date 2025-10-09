package com.metalrender.performance;

import com.metalrender.config.MetalRenderConfig;
import com.metalrender.util.FrustumCuller;
import com.metalrender.util.OcclusionCuller;
import net.minecraft.client.render.Camera;
import net.minecraft.util.math.BlockPos;
import org.joml.Matrix4f;

/**
 * High-performance rendering optimization system.
 * Provides aggressive frustum and occlusion culling to boost FPS.
 */
public final class RenderOptimizer {
    private static final RenderOptimizer INSTANCE = new RenderOptimizer();

    private final FrustumCuller frustumCuller = new FrustumCuller();
    private final OcclusionCuller occlusionCuller = new OcclusionCuller();

    private int frustumCulledThisFrame = 0;
    private int occlusionCulledThisFrame = 0;
    private int totalChunksThisFrame = 0;
    private long currentFrame = 0;

    private RenderOptimizer() {}

    public static RenderOptimizer getInstance() {
        return INSTANCE;
    }

    /**
     * Update culling systems for the current frame.
     * Call this before any chunk visibility tests.
     */
    public void updateFrame(Camera camera, Matrix4f viewProjectionMatrix) {
        currentFrame++;

        if (MetalRenderConfig.aggressiveFrustumCulling()) {
            frustumCuller.update(viewProjectionMatrix);
        }
        if (MetalRenderConfig.occlusionCulling()) {
            occlusionCuller.beginFrame(camera);
        }

        frustumCulledThisFrame = 0;
        occlusionCulledThisFrame = 0;
        totalChunksThisFrame = 0;
    }

    /**
     * Test if a chunk should be rendered.
     * Returns false if the chunk is outside the frustum or occluded.
     */
    public boolean shouldRenderChunk(BlockPos chunkPos, Camera camera) {
        totalChunksThisFrame++;

        // Convert chunk coordinates to region coordinates for frustum test
        int regionX = chunkPos.getX() >> 4;
        int regionZ = chunkPos.getZ() >> 4;
        int minY = Math.max(chunkPos.getY() - 16, -64);
        int maxY = Math.min(chunkPos.getY() + 16, 320);

        // Frustum culling (fast)
        boolean frustumVisible = true;
        if (MetalRenderConfig.aggressiveFrustumCulling()) {
            frustumVisible = frustumCuller.isRegionVisible(regionX, regionZ, minY, maxY);
            if (!frustumVisible) {
                frustumCulledThisFrame++;
                return false;
            }
        }

        // Occlusion culling (slower, only if enabled and frustum passed)
        if (MetalRenderConfig.occlusionCulling() && frustumVisible) {
            boolean occluded = occlusionCuller.isChunkOccluded(chunkPos, camera);
            if (occluded) {
                occlusionCulledThisFrame++;
                return false;
            }
        }

        // Chunk is visible
        return true;
    }

    /**
     * Get performance stats for this frame.
     */
    public PerformanceStats getFrameStats() {
        return new PerformanceStats(totalChunksThisFrame, frustumCulledThisFrame, occlusionCulledThisFrame, 0,
            currentFrame); // Simplified without cache
    }

    /**
     * Reset culling state (call when world changes significantly).
     */
    public void invalidateCache() {
        // Simplified implementation without cache
        frustumCulledThisFrame = 0;
        occlusionCulledThisFrame = 0;
        totalChunksThisFrame = 0;
    }

    public static class PerformanceStats {
        public final int totalChunks;
        public final int frustumCulled;
        public final int occlusionCulled;
        public final int cacheSize;
        public final long currentFrame;
        public final double cullPercentage;

        PerformanceStats(int total, int frustumCulled, int occlusionCulled, int cacheSize, long frame) {
            this.totalChunks = total;
            this.frustumCulled = frustumCulled;
            this.occlusionCulled = occlusionCulled;
            this.cacheSize = cacheSize;
            this.currentFrame = frame;
            int culled = frustumCulled + occlusionCulled;
            this.cullPercentage = total > 0 ? (double) culled / total * 100.0 : 0.0;
        }
    }
}