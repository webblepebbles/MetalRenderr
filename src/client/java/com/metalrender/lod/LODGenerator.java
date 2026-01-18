package com.metalrender.lod;

import com.metalrender.memory.MetalVertexLayout;
import com.metalrender.memory.VertexBufferPool;
import com.metalrender.util.VertexCompressor;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;


public final class LODGenerator {
  private static final int MAX_LEVELS = 2;
  private static final int VERTS_PER_QUAD = 6; 

  private LODGenerator() {
  }

  public static List<LevelData> generate(VertexCompressor.CompressedMesh mesh) {
    if (mesh == null || mesh.vertexCount <= 0) {
      return Collections.emptyList();
    }

    List<LevelData> levels = new ArrayList<>(MAX_LEVELS);
    float radius = mesh.bounds != null ? mesh.bounds.radius : 0.0F;
    int originalVertexCount = Math.max(0, mesh.vertexCount);

    LevelData base = createLevel(mesh.buffer(), mesh.vertexCount, 1, 0, radius, originalVertexCount);
    if (base.vertexCount > 0) {
      levels.add(base);
    }

    LevelData half = createLevel(mesh.buffer(), mesh.vertexCount, 2, 1, radius, originalVertexCount);
    if (half.vertexCount > 0) {
      levels.add(half);
    }

    return levels;
  }

  
  private static LevelData createLevel(ByteBuffer original, int vertexCount,
      int step, int level, float boundsRadius, int originalVertexCount) {
    if (vertexCount <= 0 || step <= 0) {
      return new LevelData(null, 0, level, boundsRadius, originalVertexCount, false);
    }

    int stride = MetalVertexLayout.STRIDE;
    int quadCount = vertexCount / VERTS_PER_QUAD;

    if (quadCount <= 0) {
      int totalBytes = vertexCount * stride;
      ByteBuffer source = original.duplicate().order(ByteOrder.LITTLE_ENDIAN);
      source.position(0);
      source.limit(Math.min(source.capacity(), totalBytes));

      ByteBuffer target = VertexBufferPool.shared().acquire(totalBytes);
      target.order(ByteOrder.LITTLE_ENDIAN);
      target.clear();
      target.put(source);
      target.flip();
      return new LevelData(target, vertexCount, level, boundsRadius, originalVertexCount, true);
    }
    int outputQuadCount = (quadCount + step - 1) / step;
    int outputVertexCount = outputQuadCount * VERTS_PER_QUAD;
    int outputBytes = outputVertexCount * stride;

    ByteBuffer source = original.duplicate().order(ByteOrder.LITTLE_ENDIAN);
    source.position(0);

    ByteBuffer target = VertexBufferPool.shared().acquire(outputBytes);
    target.order(ByteOrder.LITTLE_ENDIAN);
    target.clear();

    byte[] quadData = new byte[VERTS_PER_QUAD * stride];

    int emitted = 0;
    for (int q = 0; q < quadCount; q += step) {
      int quadOffset = q * VERTS_PER_QUAD * stride;
      if (quadOffset + quadData.length > source.capacity()) {
        break;
      }
      source.position(quadOffset);
      source.get(quadData, 0, quadData.length);
      target.put(quadData);
      emitted += VERTS_PER_QUAD;
    }

    if (emitted == 0) {
      target.clear();
      target.limit(0);
      return new LevelData(target, 0, level, boundsRadius, originalVertexCount, true);
    }

    target.flip();
    return new LevelData(target, emitted, level, boundsRadius, originalVertexCount, true);
  }

  public static final class LevelData {
    private final ByteBuffer data;
    public final int vertexCount;
    public final int level;
    public final float boundsRadius;
    public final int originalVertexCount;
    private final boolean pooled;
    private final int byteSize;

    private LevelData(ByteBuffer data, int vertexCount, int level,
        float boundsRadius, int originalVertexCount,
        boolean pooled) {
      this.data = data == null ? VertexBufferPool.shared().acquire(0) : data;
      this.vertexCount = vertexCount;
      this.level = level;
      this.boundsRadius = boundsRadius;
      this.originalVertexCount = originalVertexCount;
      this.pooled = pooled && this.data != null && this.data.isDirect();
      this.byteSize = Math.max(0, vertexCount * MetalVertexLayout.STRIDE);
    }

    public ByteBuffer buffer() {
      ByteBuffer duplicate = this.data.duplicate().order(ByteOrder.LITTLE_ENDIAN);
      duplicate.position(0);
      duplicate.limit(Math.min(duplicate.capacity(), this.byteSize));
      return duplicate;
    }

    public int byteSize() {
      return this.byteSize;
    }

    public void release() {
      if (this.pooled && this.data != null) {
        VertexBufferPool.shared().release(this.data);
      }
    }
  }
}
