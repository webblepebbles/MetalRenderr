package com.metalrender.sodium.backend;

import com.metalrender.config.MetalRenderConfig;
import com.metalrender.nativebridge.NativeBridge;
import com.metalrender.performance.RenderOptimizer;
import com.metalrender.performance.RenderingMetrics;
import com.metalrender.util.MetalLogger;
import com.metalrender.util.PersistentBufferArena;
import com.metalrender.util.VertexPassthrough;
import com.metalrender.util.VertexPassthrough.PassthroughMesh;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionMeshParts;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.minecraft.client.render.Camera;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

public final class MeshShaderBackend {
  private static final int MAX_CHUNK_CACHE_SIZE = 16384;
  private static final double MAX_CHUNK_DISTANCE = 2048.0;

  private final boolean meshSupported;
  private final Map<Long, ChunkMesh> chunkMeshes = new ConcurrentHashMap<>();

  private volatile ChunkMesh[] meshSnapshot = new ChunkMesh[0];
  private volatile boolean snapshotDirty = true;
  private long lastCleanupFrame = 0;
  private long frameCount = 0;

  public MeshShaderBackend() {
    this.meshSupported = NativeBridge.nSupportsMeshShaders();
    MetalLogger.info("[MetalRender] Mesh shader support: {}",
        this.meshSupported ? "available"
            : "unavailable (compute fallback)");
  }

  public int getMeshCount() {
    return this.chunkMeshes.size();
  }

  public boolean isMeshEnabled() {
    return this.meshSupported && MetalRenderConfig.meshShadersEnabled();
  }

  public void destroy() {
    this.chunkMeshes.clear();
  }

  public void uploadBuildOutput(long nativeHandle, PersistentBufferArena arena,
      ChunkBuildOutput output) {
    if (arena == null || output == null) {
      MetalLogger.info("[MeshBackend] uploadBuildOutput: arena=%s, output=%s", arena, output);
      return;
    }

    RenderSection section = output.render;
    if (section == null) {
      MetalLogger.info("[MeshBackend] uploadBuildOutput: section is null");
      return;
    }

    BlockPos origin = new BlockPos(section.getOriginX(), section.getOriginY(),
        section.getOriginZ());
    long key = origin.asLong();

    if (output.meshes == null || output.meshes.isEmpty()) {
      ChunkMesh old = this.chunkMeshes.remove(key);
      if (old != null) {
        freeMesh(old, arena);
        this.snapshotDirty = true;
      }
      return;
    }

    ChunkMesh mesh = new ChunkMesh(origin);

    for (Map.Entry<TerrainRenderPass, BuiltSectionMeshParts> entry : output.meshes.entrySet()) {
      TerrainRenderPass pass = entry.getKey();
      BuiltSectionMeshParts parts = entry.getValue();
      if (parts == null || parts.getVertexData() == null ||
          parts.getVertexData().getLength() == 0) {
        continue;
      }

      int renderLayer;
      if (pass.isTranslucent()) {
        renderLayer = 2;
      } else if (pass.supportsFragmentDiscard()) {
        renderLayer = 1;
      } else {
        renderLayer = 0;
      }

      ByteBuffer vanilla = parts.getVertexData().getDirectBuffer().duplicate().order(
          ByteOrder.LITTLE_ENDIAN);
      vanilla.clear();

      int vertexCount = parts.getVertexData().getLength() / VertexPassthrough.SODIUM_STRIDE;
      if (vertexCount <= 0) {
        continue;
      }

      PassthroughMesh passthrough = VertexPassthrough.passthrough(origin, vanilla, vertexCount);
      if (passthrough.vertexCount <= 0) {
        continue;
      }

      ByteBuffer persistent = arena.buffer();
      if (persistent == null) {
        MetalLogger.warn("[MetalRender] Persistent buffer unavailable; "
            + "skipping chunk upload at {}",
            origin);
        continue;
      }

      ByteBuffer levelBuffer = passthrough.buffer;
      int bytes = levelBuffer.remaining();
      if (bytes <= 0) {
        continue;
      }

      int offset = arena.allocate(bytes);
      if (offset < 0) {
        MetalLogger.warn("[MetalRender] Persistent buffer exhausted while "
            + "uploading chunk {}",
            origin);
        continue;
      }

      ByteBuffer target = persistent.duplicate();
      target.position(offset);
      target.limit(offset + bytes);
      ByteBuffer copy = levelBuffer.duplicate();
      target.put(copy);

      mesh.addDraw(new DrawCommand(offset, passthrough.vertexCount, bytes, renderLayer));
    }

    if (mesh.hasDraws()) {
      ChunkMesh old = this.chunkMeshes.put(key, mesh);
      if (old != null)
        freeMesh(old, arena);
      this.snapshotDirty = true;

      if (this.chunkMeshes.size() % 100 == 1) {
        MetalLogger.info("[MeshBackend] Added mesh for chunk at {}, total meshes: {}",
            origin, this.chunkMeshes.size());
      }
    } else {
      ChunkMesh old = this.chunkMeshes.remove(key);
      if (old != null) {
        freeMesh(old, arena);
        this.snapshotDirty = true;
      }
    }
  }

  public void removeChunkMesh(BlockPos chunkPos, PersistentBufferArena arena) {
    if (chunkPos != null) {
      ChunkMesh old = this.chunkMeshes.remove(chunkPos.asLong());
      if (old != null) {
        freeMesh(old, arena);
        this.snapshotDirty = true;
      }
    }
  }

  private void freeMesh(ChunkMesh mesh, PersistentBufferArena arena) {
    if (mesh == null || arena == null)
      return;
    for (DrawCommand cmd : mesh.draws) {
      arena.free(cmd.vertexOffset, cmd.allocatedBytes);
    }
  }

  private void cleanupDistantChunks(Camera camera, PersistentBufferArena arena) {
    if (this.chunkMeshes.size() < MAX_CHUNK_CACHE_SIZE) {
      return;
    }

    Vec3d cameraPos = camera.getCameraPos();
    java.util.List<Long> toRemove = new java.util.ArrayList<>();
    double maxDistSq = MAX_CHUNK_DISTANCE * MAX_CHUNK_DISTANCE;

    for (ChunkMesh mesh : this.chunkMeshes.values()) {
      double distSq = mesh.distanceSquaredTo(cameraPos);
      if (distSq > maxDistSq) {
        toRemove.add(mesh.origin.asLong());
      }
    }

    for (Long key : toRemove) {
      ChunkMesh old = this.chunkMeshes.remove(key);
      if (old != null)
        freeMesh(old, arena);
    }

    if (!toRemove.isEmpty()) {
      this.snapshotDirty = true;
      MetalLogger.info(
          "[MeshBackend] Cleaned up {} distant chunks (cache size: {} -> {})",
          toRemove.size(), this.chunkMeshes.size() + toRemove.size(),
          this.chunkMeshes.size());
    }
  }

  private static final int DRAW_CMD_SIZE = 32;
  private ByteBuffer batchBuffer = null;
  private int batchBufferCapacity = 0;

  private static final int FIELDS_PER_DRAW = 9;
  private int[] drawInfoPool = new int[8192 * FIELDS_PER_DRAW];
  private int drawInfoCount = 0;
  private long[] sortKeys = new long[8192];
  private int[] sortIndices = new int[8192];
  private int[] sortTemp = new int[8192];

  private static int mortonInterleave(int x, int z) {
    x = (x | (x << 8)) & 0x00FF00FF;
    x = (x | (x << 4)) & 0x0F0F0F0F;
    x = (x | (x << 2)) & 0x33333333;
    x = (x | (x << 1)) & 0x55555555;
    z = (z | (z << 8)) & 0x00FF00FF;
    z = (z | (z << 4)) & 0x0F0F0F0F;
    z = (z | (z << 2)) & 0x33333333;
    z = (z | (z << 1)) & 0x55555555;
    return x | (z << 1);
  }

  private static void radixSort(long[] keys, int[] indices, int[] temp, int n) {
    int[] count = new int[256];
    for (int shift = 0; shift < 48; shift += 8) {
      java.util.Arrays.fill(count, 0);
      for (int i = 0; i < n; i++) {
        count[(int) ((keys[indices[i]] >>> shift) & 0xFF)]++;
      }
      for (int i = 1; i < 256; i++) {
        count[i] += count[i - 1];
      }
      for (int i = n - 1; i >= 0; i--) {
        int bucket = (int) ((keys[indices[i]] >>> shift) & 0xFF);
        temp[--count[bucket]] = indices[i];
      }
      System.arraycopy(temp, 0, indices, 0, n);
    }
  }

  private void ensureBatchBuffer(int commandCount) {
    int needed = commandCount * DRAW_CMD_SIZE;
    if (batchBuffer == null || batchBufferCapacity < needed) {
      batchBufferCapacity = Math.max(needed, 4096 * DRAW_CMD_SIZE);
      batchBuffer = ByteBuffer.allocateDirect(batchBufferCapacity).order(ByteOrder.LITTLE_ENDIAN);
    }
    batchBuffer.clear();
  }

  public int emitDraws(long nativeHandle, PersistentBufferArena arena, RenderOptimizer optimizer,
      Camera camera) {
    int meshCount = this.chunkMeshes.size();
    if (meshCount == 0) {
      return 0;
    }

    RenderingMetrics.resetFrame();
    long currentFrame = System.nanoTime() / 16_666_666L;
    if (currentFrame - this.lastCleanupFrame > 60) {
      this.cleanupDistantChunks(camera, arena);
      this.lastCleanupFrame = currentFrame;
    }

    if (this.snapshotDirty) {
      this.meshSnapshot = this.chunkMeshes.values().toArray(new ChunkMesh[0]);
      this.snapshotDirty = false;
    }

    ChunkMesh[] snapshot = this.meshSnapshot;
    Vec3d cameraPos = camera.getCameraPos();

    drawInfoCount = 0;
    for (ChunkMesh mesh : snapshot) {
      if (!optimizer.shouldRenderChunk(mesh.origin, camera)) {
        continue;
      }

      double worldDistSq = mesh.distanceSquaredTo(cameraPos);

      for (DrawCommand draw : mesh.draws) {

        int lodLevel = MetalRenderConfig.getLodLevelForDistanceSq((float) worldDistSq);

        int needed = (drawInfoCount + 1) * FIELDS_PER_DRAW;
        if (needed > drawInfoPool.length) {
          int newSize = Math.max(needed, drawInfoPool.length * 2);
          int[] newPool = new int[newSize];
          System.arraycopy(drawInfoPool, 0, newPool, 0, drawInfoCount * FIELDS_PER_DRAW);
          drawInfoPool = newPool;
        }

        int base = drawInfoCount * FIELDS_PER_DRAW;
        drawInfoPool[base + 0] = draw.vertexOffset;
        drawInfoPool[base + 1] = draw.vertexCount;
        drawInfoPool[base + 2] = Float.floatToRawIntBits((float) mesh.origin.getX());
        drawInfoPool[base + 3] = Float.floatToRawIntBits((float) mesh.origin.getY());
        drawInfoPool[base + 4] = Float.floatToRawIntBits((float) mesh.origin.getZ());
        drawInfoPool[base + 5] = draw.renderLayer;
        drawInfoPool[base + 6] = Float.floatToRawIntBits((float) worldDistSq);
        drawInfoPool[base + 7] = lodLevel;
        int chunkX = (mesh.origin.getX() >> 4) & 0xFFFF;
        int chunkZ = (mesh.origin.getZ() >> 4) & 0xFFFF;
        drawInfoPool[base + 8] = mortonInterleave(chunkX, chunkZ);
        drawInfoCount++;
      }
    }

    if (drawInfoCount == 0) {
      return 0;
    }

    if (sortKeys.length < drawInfoCount) {
      int newCap = Math.max(drawInfoCount, sortKeys.length * 2);
      sortKeys = new long[newCap];
      sortIndices = new int[newCap];
      sortTemp = new int[newCap];
    }
    for (int i = 0; i < drawInfoCount; i++) {
      int base = i * FIELDS_PER_DRAW;
      long lod = drawInfoPool[base + 7] & 0xFFL;
      long layer = drawInfoPool[base + 5] & 0xFFL;
      long morton = drawInfoPool[base + 8] & 0xFFFFFFFFL;
      sortKeys[i] = (lod << 40) | (layer << 32) | morton;
      sortIndices[i] = i;
    }
    radixSort(sortKeys, sortIndices, sortTemp, drawInfoCount);

    ensureBatchBuffer(drawInfoCount);
    int commandIndex = 0;

    for (int si = 0; si < drawInfoCount; si++) {
      int base = sortIndices[si] * FIELDS_PER_DRAW;
      RenderingMetrics.addVertices(drawInfoPool[base + 1]);
      RenderingMetrics.addDrawCommand();

      batchBuffer.putInt(drawInfoPool[base + 0]);
      batchBuffer.putInt(drawInfoPool[base + 1]);
      batchBuffer.putFloat(Float.intBitsToFloat(drawInfoPool[base + 2]));
      batchBuffer.putFloat(Float.intBitsToFloat(drawInfoPool[base + 3]));
      batchBuffer.putFloat(Float.intBitsToFloat(drawInfoPool[base + 4]));
      batchBuffer.putInt(drawInfoPool[base + 5]);
      batchBuffer.putFloat(Float.intBitsToFloat(drawInfoPool[base + 6]));
      batchBuffer.putInt(drawInfoPool[base + 7]);

      commandIndex++;
    }

    if (commandIndex == 0) {
      return 0;
    }

    batchBuffer.flip();
    NativeBridge.nBatchDrawCommands(nativeHandle, batchBuffer, commandIndex);

    if (this.frameCount < 3 || this.frameCount % 600 == 0) {
      RenderOptimizer.PerformanceStats stats = optimizer.getFrameStats();
      MetalLogger.info("[MeshBackend] emitDraws: total=%d, distCulled=%d, frustumCulled=%d, drawn=%d (of %d meshes)",
          stats.totalChunks, optimizer.getDistanceCulledThisFrame(), optimizer.getFrustumCulledOnlyThisFrame(),
          commandIndex, meshCount);
    }
    this.frameCount++;

    return commandIndex;
  }

  private static final class ChunkMesh {
    final BlockPos origin;
    final List<DrawCommand> draws = new java.util.ArrayList<>();

    ChunkMesh(BlockPos origin) {
      this.origin = origin;
    }

    void addDraw(DrawCommand command) {
      this.draws.add(command);
    }

    boolean hasDraws() {
      return !this.draws.isEmpty();
    }

    double distanceTo(Vec3d cameraPos) {
      double centerX = (double) this.origin.getX() + 8.0 - cameraPos.x;
      double centerY = (double) this.origin.getY() + 8.0 - cameraPos.y;
      double centerZ = (double) this.origin.getZ() + 8.0 - cameraPos.z;
      return Math.sqrt(centerX * centerX + centerY * centerY +
          centerZ * centerZ);
    }

    double distanceSquaredTo(Vec3d cameraPos) {
      double centerX = (double) this.origin.getX() + 8.0 - cameraPos.x;
      double centerY = (double) this.origin.getY() + 8.0 - cameraPos.y;
      double centerZ = (double) this.origin.getZ() + 8.0 - cameraPos.z;
      return centerX * centerX + centerY * centerY + centerZ * centerZ;
    }
  }

  private static final class DrawCommand {
    final int vertexOffset;
    final int vertexCount;
    final int allocatedBytes;
    final int renderLayer;

    DrawCommand(int vertexOffset, int vertexCount, int allocatedBytes, int renderLayer) {
      this.vertexOffset = vertexOffset;
      this.vertexCount = vertexCount;
      this.allocatedBytes = allocatedBytes;
      this.renderLayer = renderLayer;
    }
  }
}
