package com.metalrender.culling;

import com.metalrender.nativebridge.NativeBridge;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.render.Camera;
import net.minecraft.util.math.BlockPos;

public final class OcclusionCuller {
  private static final int COMPONENTS_PER_AABB = 6;
  private static final int MAX_QUERIES = 65536;

  private final HiZBuffer hiZBuffer = new HiZBuffer();
  private final Map<Long, Query> queryCache = new HashMap<>();
  private final ArrayList<Query> pending = new ArrayList<>(1024);
  private final ByteBuffer aabbBytes;
  private final FloatBuffer aabbFloats;
  private final ByteBuffer resultBytes;
  private boolean supported;
  private long contextHandle;
  private int frameIndex;

  public OcclusionCuller() {
    this.aabbBytes =
        ByteBuffer
            .allocateDirect(MAX_QUERIES * COMPONENTS_PER_AABB * Float.BYTES)
            .order(ByteOrder.nativeOrder());
    this.aabbFloats = this.aabbBytes.asFloatBuffer();
    this.resultBytes =
        ByteBuffer.allocateDirect(MAX_QUERIES).order(ByteOrder.nativeOrder());
  }

  public void beginFrame(long ctxHandle, Camera camera, float[] viewProj,
                         int width, int height) {
    this.contextHandle = ctxHandle;
    this.frameIndex++;
    this.pending.clear();
    this.aabbFloats.clear();
    this.resultBytes.clear();
    if (ctxHandle == 0L) {
      this.supported = false;
      return;
    }
    if (!this.supported) {
      this.supported = NativeBridge.nSupportsHiZ(ctxHandle);
    }
    if (!this.supported) {
      return;
    }
    if (this.hiZBuffer.ensure(ctxHandle, Math.max(1, width),
                              Math.max(1, height))) {
      NativeBridge.nOcclusionBegin(ctxHandle, this.hiZBuffer.getHandle(),
                                   viewProj);
    }
  }

  public boolean isChunkOccluded(BlockPos chunkPos, int minBlockY,
                                 int maxBlockY) {
    if (!this.supported || this.contextHandle == 0L) {
      return false;
    }
    if (this.pending.size() >= MAX_QUERIES) {
      return false;
    }
    long key = computeKey(chunkPos);
    Query query = this.queryCache.get(key);
    if (query == null) {
      query = new Query();
      query.visible = true;
      this.queryCache.put(key, query);
    }
    double baseX = (double)(chunkPos.getX() << 4);
    double baseZ = (double)(chunkPos.getZ() << 4);
    double minX = baseX;
    double minY = (double)minBlockY;
    double minZ = baseZ;
    double maxX = baseX + 16.0D;
    double maxY = (double)maxBlockY;
    double maxZ = baseZ + 16.0D;
    int writeIndex = this.pending.size();
    this.pending.add(query);
    int bufferIndex = writeIndex * COMPONENTS_PER_AABB;
    this.aabbFloats.position(bufferIndex);
    this.aabbFloats.put((float)minX);
    this.aabbFloats.put((float)minY);
    this.aabbFloats.put((float)minZ);
    this.aabbFloats.put((float)maxX);
    this.aabbFloats.put((float)maxY);
    this.aabbFloats.put((float)maxZ);
    query.lastSubmittedFrame = this.frameIndex;
    return !query.visible;
  }

  public void resolve() {
    if (!this.supported || this.pending.isEmpty() || this.contextHandle == 0L ||
        this.hiZBuffer.getHandle() == 0L) {
      return;
    }
    int queryCount = this.pending.size();
    this.aabbFloats.position(queryCount * COMPONENTS_PER_AABB);
    this.aabbFloats.flip();
    this.resultBytes.position(0);
    this.resultBytes.limit(queryCount);
    NativeBridge.nOcclusionEvaluate(this.contextHandle,
                                    this.hiZBuffer.getHandle(), this.aabbFloats,
                                    queryCount, this.resultBytes);
    for (int i = 0; i < queryCount; ++i) {
      byte state = this.resultBytes.get(i);
      Query query = this.pending.get(i);
      query.visible = state == 0;
    }
    this.pending.clear();
    sweepCache();
  }

  public void destroy(long ctxHandle) {
    this.hiZBuffer.destroy(ctxHandle);
    this.queryCache.clear();
    this.pending.clear();
    this.supported = false;
    this.contextHandle = 0L;
  }

  private void sweepCache() {
    if (this.queryCache.size() <= MAX_QUERIES / 4) {
      return;
    }
    int threshold = this.frameIndex - 240;
    this.queryCache.values().removeIf(
        query -> query.lastSubmittedFrame < threshold);
  }

  private static long computeKey(BlockPos pos) {
    long x = (long)pos.getX() & 0x3FFFFFFL;
    long y = (long)pos.getY() & 0xFFF;
    long z = (long)pos.getZ() & 0x3FFFFFFL;
    return (x << 52) | (y << 40) | z;
  }

  private static final class Query {
    boolean visible;
    int lastSubmittedFrame;
  }
}
