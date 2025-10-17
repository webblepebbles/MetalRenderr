package com.metalrender.performance;

import com.metalrender.config.MetalRenderConfig;
import com.metalrender.util.FrustumCuller;
import com.metalrender.util.OcclusionCuller;
import net.minecraft.client.render.Camera;
import net.minecraft.util.math.BlockPos;
import org.joml.Matrix4f;

public final class RenderOptimizer {
   private static final RenderOptimizer INSTANCE = new RenderOptimizer();
   private FrustumCuller frustumCuller;
   private OcclusionCuller occlusionCuller;
   private int frustumCulledThisFrame = 0;
   private int occlusionCulledThisFrame = 0;
   private int totalChunksThisFrame = 0;
   private long currentFrame = 0L;
   private boolean initialized = false;

   private RenderOptimizer() {
   }

   public static RenderOptimizer getInstance() {
      return INSTANCE;
   }

   public void updateFrame(Camera camera, Matrix4f viewProjectionMatrix) {
      if (!this.initialized) {
         this.frustumCuller = new FrustumCuller();
         this.occlusionCuller = new OcclusionCuller();
         this.initialized = true;
      }

      ++this.currentFrame;
      if (MetalRenderConfig.aggressiveFrustumCulling()) {
         this.frustumCuller.update(viewProjectionMatrix);
      }

      if (MetalRenderConfig.occlusionCulling()) {
         this.occlusionCuller.beginFrame(camera);
      }

      this.frustumCulledThisFrame = 0;
      this.occlusionCulledThisFrame = 0;
      this.totalChunksThisFrame = 0;
   }

   public boolean shouldRenderChunk(BlockPos chunkPos, Camera camera) {
      ++this.totalChunksThisFrame;
      int regionX = chunkPos.getX() >> 4;
      int regionZ = chunkPos.getZ() >> 4;
      int minY = Math.max(chunkPos.getY() - 16, -64);
      int maxY = Math.min(chunkPos.getY() + 16, 320);
      boolean frustumVisible = true;
      if (MetalRenderConfig.aggressiveFrustumCulling()) {
         frustumVisible = this.frustumCuller.isRegionVisible(regionX, regionZ, minY, maxY);
         if (!frustumVisible) {
            ++this.frustumCulledThisFrame;
            return false;
         }
      }

      if (MetalRenderConfig.occlusionCulling() && frustumVisible) {
         boolean occluded = this.occlusionCuller.isChunkOccluded(chunkPos, camera);
         if (occluded) {
            ++this.occlusionCulledThisFrame;
            return false;
         }
      }

      return true;
   }

   public RenderOptimizer.PerformanceStats getFrameStats() {
      return new RenderOptimizer.PerformanceStats(this.totalChunksThisFrame, this.frustumCulledThisFrame, this.occlusionCulledThisFrame, 0, this.currentFrame);
   }

   public void invalidateCache() {
      this.frustumCulledThisFrame = 0;
      this.occlusionCulledThisFrame = 0;
      this.totalChunksThisFrame = 0;
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
         this.cullPercentage = total > 0 ? (double)culled / (double)total * 100.0D : 0.0D;
      }
   }
}
