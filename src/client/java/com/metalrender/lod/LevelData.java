package com.metalrender.lod;

import com.metalrender.util.VertexCompressor;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class LevelData {
  private final ByteBuffer data;
  public final int vertexCount;
  public final int level;

  private static final int STEP_BASE = 1;
  private static final int STEP_HALF = 2;
  private static final int STEP_QUARTER = 4;
  private static final int STEP_EIGHTH = 8;
  private static final int STEP_THIRTY_SECOND = 32;
  private static final int MAX_LEVELS = 5;

  private LevelData(ByteBuffer data, int vertexCount, int level) {
    this.data = data;
    this.vertexCount = vertexCount;
    this.level = level;
  }

  public ByteBuffer buffer() {
    return this.data.duplicate().order(ByteOrder.LITTLE_ENDIAN);
  }

  public static List<LevelData> generate(VertexCompressor.CompressedMesh mesh) {
    if (mesh == null || mesh.vertexCount <= 0) {
      return Collections.emptyList();
    }

    List<LevelData> levels = new ArrayList<>(MAX_LEVELS);

    int step0 = (int)Math.round(1.0 / 0.9);
    LevelData base = createLevel(mesh.buffer(), mesh.vertexCount,
                                 Math.max(STEP_BASE, step0), 0);
    if (base.vertexCount > 0) {
      levels.add(base);
    }

    int step1 = (int)Math.round(1.0 / 0.45);
    LevelData half = createLevel(mesh.buffer(), mesh.vertexCount,
                                 Math.max(STEP_HALF, step1), 1);
    if (half.vertexCount > 0) {
      levels.add(half);
    }

    int step2 = (int)Math.round(1.0 / 0.225);
    LevelData quarter = createLevel(mesh.buffer(), mesh.vertexCount,
                                    Math.max(STEP_QUARTER, step2), 2);
    if (quarter.vertexCount > 0) {
      levels.add(quarter);
    }

    LevelData eighth =
        createLevel(mesh.buffer(), mesh.vertexCount, STEP_EIGHTH, 3);
    if (eighth.vertexCount > 0) {
      levels.add(eighth);
    }

    LevelData thirtySecond =
        createLevel(mesh.buffer(), mesh.vertexCount, STEP_THIRTY_SECOND, 4);
    if (thirtySecond.vertexCount > 0) {
      levels.add(thirtySecond);
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
}
