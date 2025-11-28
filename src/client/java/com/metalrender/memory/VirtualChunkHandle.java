package com.metalrender.memory;

import java.nio.file.Path;
import net.minecraft.util.math.BlockPos;

public final class VirtualChunkHandle {
  public final BlockPos origin;
  public final int lodLevel;
  public final int vertexCount;
  public final float boundsRadius;
  public final int originalVertexCount;
  public final Path path;

  VirtualChunkHandle(BlockPos origin, int lodLevel, int vertexCount,
      float boundsRadius, int originalVertexCount, Path path) {
    this.origin = origin;
    this.lodLevel = lodLevel;
    this.vertexCount = vertexCount;
    this.boundsRadius = boundsRadius;
    this.originalVertexCount = originalVertexCount;
    this.path = path;
  }
}
