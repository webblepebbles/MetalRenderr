package com.metalrender.culling;

import com.metalrender.util.FrustumCuller;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.util.math.BlockPos;

public final class RegionManager {
  private static final int REGION_CHUNK_SIZE = 16;
  private static final int REGION_BLOCK_SIZE = REGION_CHUNK_SIZE * 16;
  private static final int VISIBILITY_HISTORY_FRAMES = 6;

  private final Map<Long, Region> regions = new HashMap<>();
  private int frameIndex;

  public void beginFrame() {
    ++this.frameIndex;
    if (this.frameIndex == Integer.MAX_VALUE) {
      this.frameIndex = VISIBILITY_HISTORY_FRAMES;
    }
  }

  public RegionVisibility evaluate(BlockPos chunkOrigin, int minBlockY,
                                   int maxBlockY, FrustumCuller frustum) {
    Region region = this.getOrCreateRegion(chunkOrigin);
    region.expandVerticalRange(minBlockY, maxBlockY);
    if (region.lastEvaluatedFrame == this.frameIndex) {
      return region.cachedVisibility;
    }

    region.lastEvaluatedFrame = this.frameIndex;
    boolean frustumVisible =
        frustum == null ||
        frustum.aabbIntersectsFrustum(region.minBlockX, region.minBlockY,
                                      region.minBlockZ, region.maxBlockX,
                                      region.maxBlockY, region.maxBlockZ);
    if (!frustumVisible) {
      region.cachedVisibility = RegionVisibility.FRUSTUM_CULLED;
      region.wasVisible = false;
      return region.cachedVisibility;
    }

    if (region.wasVisible &&
        region.lastVisibleFrame + VISIBILITY_HISTORY_FRAMES > this.frameIndex) {
      region.cachedVisibility = RegionVisibility.VISIBLE_CACHED;
    } else {
      region.cachedVisibility = RegionVisibility.VISIBLE_REQUIRES_CHUNK_TESTS;
    }
    return region.cachedVisibility;
  }

  public void markRegionVisible(BlockPos chunkOrigin) {
    Region region = this.getOrCreateRegion(chunkOrigin);
    region.wasVisible = true;
    region.lastVisibleFrame = this.frameIndex;
    region.cachedVisibility = RegionVisibility.VISIBLE_CACHED;
  }

  public void markRegionHidden(BlockPos chunkOrigin) {
    Region region = this.getOrCreateRegion(chunkOrigin);
    region.wasVisible = false;
    region.cachedVisibility = RegionVisibility.FRUSTUM_CULLED;
  }

  public void sweep() {
    if (this.regions.size() <= 4096) {
      return;
    }
    int threshold = this.frameIndex - 240;
    this.regions.values().removeIf(
        region -> region.lastTouchedFrame < threshold);
  }

  private Region getOrCreateRegion(BlockPos chunkOrigin) {
    int chunkX = chunkOrigin.getX() >> 4;
    int chunkZ = chunkOrigin.getZ() >> 4;
    int regionX = chunkX >> 4;
    int regionZ = chunkZ >> 4;
    long key = (((long)regionX) << 32) ^ ((long)regionZ & 0xFFFFFFFFL);
    Region region = this.regions.get(key);
    if (region == null) {
      region = new Region(regionX, regionZ);
      this.regions.put(key, region);
    }
    region.lastTouchedFrame = this.frameIndex;
    return region;
  }

  public enum RegionVisibility {
    FRUSTUM_CULLED,
    VISIBLE_REQUIRES_CHUNK_TESTS,
    VISIBLE_CACHED
  }

  private static final class Region {
    final float minBlockX;
    final float minBlockZ;
    final float maxBlockX;
    final float maxBlockZ;
    float minBlockY;
    float maxBlockY;
    int lastEvaluatedFrame;
    int lastVisibleFrame;
    int lastTouchedFrame;
    boolean wasVisible;
    RegionVisibility cachedVisibility;

    Region(int regionX, int regionZ) {
      float baseX = regionX * (float)REGION_BLOCK_SIZE;
      float baseZ = regionZ * (float)REGION_BLOCK_SIZE;
      this.minBlockX = baseX;
      this.minBlockZ = baseZ;
      this.maxBlockX = baseX + REGION_BLOCK_SIZE;
      this.maxBlockZ = baseZ + REGION_BLOCK_SIZE;
      this.minBlockY = Float.POSITIVE_INFINITY;
      this.maxBlockY = Float.NEGATIVE_INFINITY;
      this.cachedVisibility = RegionVisibility.VISIBLE_REQUIRES_CHUNK_TESTS;
    }

    void expandVerticalRange(int minY, int maxY) {
      if (minY < this.minBlockY) {
        this.minBlockY = minY;
      }
      if (maxY > this.maxBlockY) {
        this.maxBlockY = maxY;
      }
    }
  }
}
