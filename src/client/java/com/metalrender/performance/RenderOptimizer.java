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

  private static final int CULL_INTERVAL = 8;
  private static final int REGION_SHIFT = 2;
  private static final int REGION_SIZE = 1 << REGION_SHIFT;

  private static final int CHUNK_CACHE_BITS = 16;
  private static final int CHUNK_CACHE_SIZE = 1 << CHUNK_CACHE_BITS;
  private static final int CHUNK_CACHE_MASK = CHUNK_CACHE_SIZE - 1;
  private final long[] chunkCacheKeys = new long[CHUNK_CACHE_SIZE];
  private final byte[] chunkCacheVals = new byte[CHUNK_CACHE_SIZE];
  private static final byte CACHE_EMPTY = 0;
  private static final byte CACHE_VISIBLE = 1;
  private static final byte CACHE_HIDDEN = 2;

  private static final int REGION_CACHE_BITS = 12;
  private static final int REGION_CACHE_SIZE = 1 << REGION_CACHE_BITS;
  private static final int REGION_CACHE_MASK = REGION_CACHE_SIZE - 1;
  private final long[] regionCacheKeys = new long[REGION_CACHE_SIZE];
  private final byte[] regionCacheVals = new byte[REGION_CACHE_SIZE];

  private long lastFullCullFrame = -CULL_INTERVAL;
  private boolean fullCullThisFrame = false;

  private float prevCamX, prevCamY, prevCamZ;
  private float camDeltaX, camDeltaY, camDeltaZ;
  private float camDeltaLenSq;

  private RenderOptimizer() {
  }

  public static RenderOptimizer getInstance() {
    return INSTANCE;
  }

  public void updateFrame(long nativeHandle, Camera camera,
      Matrix4f viewProjectionMatrix, int width,
      int height) {
    if (!this.initialized) {
      this.frustumCuller = new FrustumCuller();
      this.occlusionCuller = new OcclusionCuller();
      this.regionManager = new RegionManager();
      this.initialized = true;
    }

    ++this.currentFrame;
    this.viewportWidth = Math.max(1, width);
    this.viewportHeight = Math.max(1, height);

    this.fullCullThisFrame = (this.currentFrame - this.lastFullCullFrame) >= CULL_INTERVAL;

    if (this.fullCullThisFrame) {
      this.lastFullCullFrame = this.currentFrame;
      java.util.Arrays.fill(this.regionCacheVals, CACHE_EMPTY);
      java.util.Arrays.fill(this.chunkCacheVals, CACHE_EMPTY);
    }

    if (this.frustumCuller != null) {
      this.frustumCuller.update(viewProjectionMatrix);
    }

    if (camera != null) {
      net.minecraft.util.math.Vec3d cPos = camera.getCameraPos();
      float cx = (float) cPos.x;
      float cy = (float) cPos.y;
      float cz = (float) cPos.z;
      this.camDeltaX = cx - this.prevCamX;
      this.camDeltaY = cy - this.prevCamY;
      this.camDeltaZ = cz - this.prevCamZ;
      this.camDeltaLenSq = this.camDeltaX * this.camDeltaX
          + this.camDeltaY * this.camDeltaY
          + this.camDeltaZ * this.camDeltaZ;
      this.prevCamX = cx;
      this.prevCamY = cy;
      this.prevCamZ = cz;

      if (this.camDeltaLenSq > 16.0f) {
        this.fullCullThisFrame = true;
        this.lastFullCullFrame = this.currentFrame;
        java.util.Arrays.fill(this.regionCacheVals, CACHE_EMPTY);
        java.util.Arrays.fill(this.chunkCacheVals, CACHE_EMPTY);
      }
    }

    if (MetalRenderConfig.occlusionCulling()) {
      viewProjectionMatrix.get(this.viewProjArray);
      if (this.occlusionCuller != null) {
        this.occlusionCuller.beginFrame(nativeHandle, camera,
            this.viewProjArray, this.viewportWidth,
            this.viewportHeight);
      }
    }

    if (this.regionManager != null) {
      this.regionManager.beginFrame();
      this.regionManager.sweep();
    }

    this.frustumCulledThisFrame = 0;
    this.occlusionCulledThisFrame = 0;
    this.totalChunksThisFrame = 0;
    this.distanceCulledThisFrame = 0;
    this.frustumCulledOnlyThisFrame = 0;
    this.cachedCullHitsThisFrame = 0;
    this.regionCulledThisFrame = 0;
  }

  private long debugLogCounter = 0;
  private int distanceCulledThisFrame = 0;
  private int frustumCulledOnlyThisFrame = 0;
  private int cachedCullHitsThisFrame = 0;
  private int regionCulledThisFrame = 0;

  public int getDistanceCulledThisFrame() {
    return this.distanceCulledThisFrame;
  }

  public int getFrustumCulledOnlyThisFrame() {
    return this.frustumCulledOnlyThisFrame;
  }

  public int getCachedCullHitsThisFrame() {
    return this.cachedCullHitsThisFrame;
  }

  public int getRegionCulledThisFrame() {
    return this.regionCulledThisFrame;
  }

  private static long chunkKey(int x, int y, int z) {
    return ((long) (x & 0xFFFFF)) | ((long) (y & 0xFFF) << 20) | ((long) (z & 0xFFFFF) << 32);
  }

  private static long regionKey(int rx, int ry, int rz) {
    return ((long) (rx & 0x3FFFF)) | ((long) (ry & 0xFFF) << 18) | ((long) (rz & 0x3FFFF) << 30);
  }

  private boolean isRegionVisible(int chunkX, int chunkY, int chunkZ, net.minecraft.util.math.Vec3d camPos) {
    int rx = chunkX >> REGION_SHIFT;
    int ry = chunkY >> REGION_SHIFT;
    int rz = chunkZ >> REGION_SHIFT;
    long rk = regionKey(rx, ry, rz);
    int slot = (int) (rk ^ (rk >>> 32)) & REGION_CACHE_MASK;

    if (this.regionCacheVals[slot] != CACHE_EMPTY && this.regionCacheKeys[slot] == rk) {
      return this.regionCacheVals[slot] == CACHE_VISIBLE;
    }

    float regionWorldX = (rx << REGION_SHIFT) * 16.0f;
    float regionWorldY = (ry << REGION_SHIFT) * 16.0f;
    float regionWorldZ = (rz << REGION_SHIFT) * 16.0f;
    float halfSpan = REGION_SIZE * 8.0f;

    float centerX = regionWorldX + halfSpan;
    float centerY = regionWorldY + halfSpan;
    float centerZ = regionWorldZ + halfSpan;
    float radius = halfSpan * 1.733f;

    boolean vis = true;
    if (this.frustumCuller != null && this.frustumCuller.isValid()) {
      float relX = centerX - (float) camPos.x;
      float relY = centerY - (float) camPos.y;
      float relZ = centerZ - (float) camPos.z;
      vis = this.frustumCuller.isSphereVisible(relX, relY, relZ, radius);
    }

    this.regionCacheKeys[slot] = rk;
    this.regionCacheVals[slot] = vis ? CACHE_VISIBLE : CACHE_HIDDEN;
    return vis;
  }

  public boolean shouldRenderChunk(BlockPos chunkPos, Camera camera) {
    ++this.totalChunksThisFrame;

    if (camera == null) {
      return true;
    }

    int cx = chunkPos.getX() >> 4;
    int cy = chunkPos.getY() >> 4;
    int cz = chunkPos.getZ() >> 4;

    net.minecraft.util.math.Vec3d camPos = camera.getCameraPos();

    if (!isRegionVisible(cx, cy, cz, camPos)) {
      ++this.frustumCulledThisFrame;
      ++this.regionCulledThisFrame;
      return false;
    }

    long ck = chunkKey(cx, cy, cz);
    int slot = (int) (ck ^ (ck >>> 32)) & CHUNK_CACHE_MASK;

    if (!this.fullCullThisFrame && this.chunkCacheVals[slot] != CACHE_EMPTY && this.chunkCacheKeys[slot] == ck) {
      ++this.cachedCullHitsThisFrame;
      boolean vis = this.chunkCacheVals[slot] == CACHE_VISIBLE;
      if (!vis)
        ++this.frustumCulledThisFrame;
      return vis;
    }

    double dx = chunkPos.getX() + 8.0 - camPos.x;
    double dy = chunkPos.getY() + 8.0 - camPos.y;
    double dz = chunkPos.getZ() + 8.0 - camPos.z;
    double distSq = dx * dx + dy * dy + dz * dz;
    int renderDistance = MetalRenderConfig.extremeRenderDistance();
    double maxDist = (renderDistance + 2) * 16.0;
    if (distSq > maxDist * maxDist) {
      ++this.frustumCulledThisFrame;
      ++this.distanceCulledThisFrame;
      this.chunkCacheKeys[slot] = ck;
      this.chunkCacheVals[slot] = CACHE_HIDDEN;
      return false;
    }

    if (this.frustumCuller != null && this.frustumCuller.isValid()) {
      float minX = (float) (chunkPos.getX() - camPos.x) - 2.0f;
      float minY = (float) (chunkPos.getY() - camPos.y) - 2.0f;
      float minZ = (float) (chunkPos.getZ() - camPos.z) - 2.0f;
      float maxX = minX + 20.0f;
      float maxY = minY + 20.0f;
      float maxZ = minZ + 20.0f;

      if (!this.frustumCuller.aabbIntersectsFrustum(minX, minY, minZ, maxX, maxY, maxZ)) {
        ++this.frustumCulledThisFrame;
        ++this.frustumCulledOnlyThisFrame;
        this.chunkCacheKeys[slot] = ck;
        this.chunkCacheVals[slot] = CACHE_HIDDEN;
        return false;
      }
    }

    this.chunkCacheKeys[slot] = ck;
    this.chunkCacheVals[slot] = CACHE_VISIBLE;
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
    java.util.Arrays.fill(this.chunkCacheVals, CACHE_EMPTY);
    java.util.Arrays.fill(this.regionCacheVals, CACHE_EMPTY);
    this.lastFullCullFrame = -CULL_INTERVAL;
  }

  public boolean isFullCullFrame() {
    return this.fullCullThisFrame;
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
