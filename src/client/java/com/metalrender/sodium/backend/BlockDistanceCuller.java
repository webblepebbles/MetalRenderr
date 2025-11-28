package com.metalrender.sodium.backend;

import com.metalrender.config.MetalRenderConfig;

public final class BlockDistanceCuller {
  public enum BlockCategory {
    DECORATIVE,
    FUNCTIONAL,
    STRUCTURAL;
  }

  private BlockDistanceCuller() {}

  public static BlockCategory categorize(float boundsRadius, int vertexCount) {
    if (!MetalRenderConfig.blockCullingEnabled()) {
      return BlockCategory.STRUCTURAL;
    }

    if (boundsRadius <= 1.5F && vertexCount <= 128) {
      return BlockCategory.DECORATIVE;
    }

    if (boundsRadius <= 4.0F && vertexCount <= 2048) {
      return BlockCategory.FUNCTIONAL;
    }

    return BlockCategory.STRUCTURAL;
  }

  public static boolean shouldRender(BlockCategory category, double distanceSq,
                              float boundsRadius) {
    if (!MetalRenderConfig.blockCullingEnabled()) {
      return true;
    }

    double threshold = distanceThreshold(category);
    double maxDistance = Math.max(8.0D, threshold - (boundsRadius * 2.0));
    double limitSq = maxDistance * maxDistance;
    return distanceSq <= limitSq;
  }

  public static double distanceThreshold(BlockCategory category) {
    return switch (category) {
      case DECORATIVE -> MetalRenderConfig.blockCullingDecorativeDistance();
      case FUNCTIONAL -> MetalRenderConfig.blockCullingFunctionalDistance();
      case STRUCTURAL -> MetalRenderConfig.blockCullingStructuralDistance();
    };
  }
}
