package com.metalrender.lod;

import com.metalrender.util.VertexCompressor;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Generates simplified LOD vertex buffers by sampling the compressed vertex
 * stream produced by {@link VertexCompressor}. The generator produces up to
 * three levels: full detail, medium (1/2 vertices) and far (1/4 vertices). The
 * output buffers preserve the original layout making them compatible with the
 * persistent buffer arena.
 */
public final class LODGenerator {
  private static final int MAX_LEVELS = 3;

  private LODGenerator() {}

  public static List<LevelData> generate(VertexCompressor.CompressedMesh mesh) {
    if (mesh == null || mesh.vertexCount <= 0) {
      return Collections.emptyList();
    }

    List<LevelData> levels = new ArrayList<>(MAX_LEVELS);
    LevelData base = createLevel(mesh.buffer(), mesh.vertexCount, 1, 0);
    if (base.vertexCount > 0) {
      levels.add(base);
    }

    LevelData half = createLevel(mesh.buffer(), mesh.vertexCount, 2, 1);
    if (half.vertexCount > 0) {
      levels.add(half);
    }

    LevelData quarter = createLevel(mesh.buffer(), mesh.vertexCount, 4, 2);
    if (quarter.vertexCount > 0) {
      levels.add(quarter);
    }
    return levels;
  }

  private static LevelData createLevel(ByteBuffer original, int vertexCount,
                                       int step, int level) {
    ByteBuffer source = original.duplicate().order(ByteOrder.LITTLE_ENDIAN);
    source.clear();
    if (vertexCount <= 0 || step <= 0) {
      return new LevelData(source, vertexCount, level);
    }

    int stride = VertexCompressor.OUTPUT_STRIDE;
    int estimated = Math.max(3, (vertexCount + step - 1) / step);
    int allocSize = (int)Math.ceil(estimated * 1.1) * stride;
    ByteBuffer target =
        ByteBuffer.allocateDirect(allocSize).order(ByteOrder.LITTLE_ENDIAN);
    byte[] scratch = new byte[stride];
    int emitted = 0;
    int capacity = source.capacity();
    for (int vertexIndex = 0; vertexIndex < vertexCount; vertexIndex += step) {
      int position = vertexIndex * stride;
      if (position + stride > capacity) {
        break;
      }
      source.position(position);
      source.get(scratch, 0, stride);
      target.put(scratch, 0, stride);
      emitted++;
    }
    if (emitted == 0) {
      return new LevelData(
          ByteBuffer.allocateDirect(0).order(ByteOrder.LITTLE_ENDIAN), 0,
          level);
    }
    target.limit(emitted * stride);
    target.position(0);
    return new LevelData(target, emitted, level);
  }

  public static final class LevelData {
    private final ByteBuffer data;
    public final int vertexCount;
    public final int level;

    private LevelData(ByteBuffer data, int vertexCount, int level) {
      this.data = data;
      this.vertexCount = vertexCount;
      this.level = level;
    }

    public ByteBuffer buffer() {
      return this.data.duplicate().order(ByteOrder.LITTLE_ENDIAN);
    }
  }
}
