package com.metalrender.performance;

import com.metalrender.config.MetalRenderConfig;
import com.metalrender.culling.OcclusionCuller;
import com.metalrender.culling.RegionManager;
import com.metalrender.util.FrustumCuller;
import net.minecraft.client.render.Camera;
import net.minecraft.util.math.BlockPos;
import org.joml.Matrix4f;

public final class RenderOptimizer {
  private static final RenderOptimizer INSTANCE = new RenderOptimizer();
  private FrustumCuller frustumCuller;
  private OcclusionCuller occlusionCuller;
  private RegionManager regionManager;
  private int frustumCulledThisFrame = 0;
  private int occlusionCulledThisFrame = 0;
  private int totalChunksThisFrame = 0;
  private long currentFrame = 0L;
  private boolean initialized = false;
  private int viewportWidth = 1;
  private int viewportHeight = 1;
  private final float[] viewProjArray = new float[16];

  private RenderOptimizer() {
  }

  public static RenderOptimizer getInstance() {
    return INSTANCE;
  }

  private boolean shouldInitialize() {
    return MetalRenderConfig.aggressiveFrustumCulling() ||
        MetalRenderConfig.occlusionCulling();
  }

  public void updateFrame(long nativeHandle, Camera camera,
      Matrix4f viewProjectionMatrix, int width,
      int height) {
    if (!this.initialized && shouldInitialize()) {
      this.frustumCuller = new FrustumCuller();
      this.occlusionCuller = new OcclusionCuller();
      this.regionManager = new RegionManager();
      this.initialized = true;
    }

    ++this.currentFrame;
    this.viewportWidth = Math.max(1, width);
    this.viewportHeight = Math.max(1, height);

    if (this.frustumCuller != null && MetalRenderConfig.aggressiveFrustumCulling()) {
      this.frustumCuller.update(viewProjectionMatrix);
    }

    if (this.occlusionCuller != null && MetalRenderConfig.occlusionCulling()) {
      viewProjectionMatrix.get(this.viewProjArray);
      this.occlusionCuller.beginFrame(nativeHandle, camera,
          this.viewProjArray, this.viewportWidth,
          this.viewportHeight);
    }

    if (this.regionManager != null) {
      this.regionManager.beginFrame();
      this.regionManager.sweep();
    }

    this.frustumCulledThisFrame = 0;
    this.occlusionCulledThisFrame = 0;
    this.totalChunksThisFrame = 0;
  }

  public boolean shouldRenderChunk(BlockPos chunkPos, Camera camera) {
    ++this.totalChunksThisFrame;

    if (!MetalRenderConfig.aggressiveFrustumCulling() &&
        !MetalRenderConfig.occlusionCulling()) {
      return true;
    }

    int minY = Math.max(chunkPos.getY(), -64);
    int maxY = Math.min(chunkPos.getY() + 16, 320);
    boolean frustumVisible = true;
    boolean skipOcclusion = false;

    if (this.regionManager != null) {
      RegionManager.RegionVisibility regionVisibility = this.regionManager.evaluate(
          chunkPos, minY, maxY,
          MetalRenderConfig.aggressiveFrustumCulling() ? this.frustumCuller
              : null);
      if (regionVisibility == RegionManager.RegionVisibility.FRUSTUM_CULLED) {
        ++this.frustumCulledThisFrame;
        return false;
      }
      frustumVisible = regionVisibility != RegionManager.RegionVisibility.FRUSTUM_CULLED;
      skipOcclusion = regionVisibility == RegionManager.RegionVisibility.VISIBLE_CACHED;
    } else if (MetalRenderConfig.aggressiveFrustumCulling()) {
      frustumVisible = this.frustumCuller.isRegionVisible(
          chunkPos.getX() >> 4, chunkPos.getZ() >> 4, minY, maxY);
      if (!frustumVisible) {
        ++this.frustumCulledThisFrame;
        return false;
      }
    }

    if (MetalRenderConfig.occlusionCulling() && frustumVisible) {
      boolean occluded = false;
      if (!skipOcclusion && this.occlusionCuller != null) {
        occluded = this.occlusionCuller.isChunkOccluded(chunkPos, minY, maxY);
      }
      if (occluded) {
        ++this.occlusionCulledThisFrame;
        if (this.regionManager != null) {
          this.regionManager.markRegionHidden(chunkPos);
        }
        return false;
      }
    }

    if (this.regionManager != null) {
      this.regionManager.markRegionVisible(chunkPos);
    }

    return true;
  }

  public void finalizeFrame() {
    if (MetalRenderConfig.occlusionCulling() && this.occlusionCuller != null) {
      this.occlusionCuller.resolve();
    }
  }

  public RenderOptimizer.PerformanceStats getFrameStats() {
    return new RenderOptimizer.PerformanceStats(
        this.totalChunksThisFrame, this.frustumCulledThisFrame,
        this.occlusionCulledThisFrame, 0, this.currentFrame);
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

    PerformanceStats(int total, int frustumCulled, int occlusionCulled,
        int cacheSize, long frame) {
      this.totalChunks = total;
      this.frustumCulled = frustumCulled;
      this.occlusionCulled = occlusionCulled;
      this.cacheSize = cacheSize;
      this.currentFrame = frame;
      int culled = frustumCulled + occlusionCulled;
      this.cullPercentage = total > 0 ? (double) culled / (double) total * 100.0D : 0.0D;
    }
  }
}
