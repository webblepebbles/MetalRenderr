package com.metalrender.sodium.backend;

import com.metalrender.config.MetalRenderConfig;
import com.metalrender.lod.LODGenerator;
import com.metalrender.lod.LODGenerator.LevelData;
import com.metalrender.nativebridge.NativeBridge;
import com.metalrender.performance.RenderOptimizer;
import com.metalrender.performance.RenderingMetrics;
import com.metalrender.util.MetalLogger;
import com.metalrender.util.PersistentBufferArena;
import com.metalrender.util.VertexCompressor;
import com.metalrender.util.VertexCompressor.CompressedMesh;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionMeshParts;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.minecraft.client.render.Camera;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

public final class MeshShaderBackend {
  private static final int MAX_LOD_LEVELS = 3;
  private static final int MAX_CHUNK_CACHE_SIZE = 8192;
  private static final double MAX_CHUNK_DISTANCE = 1024.0;

  private final boolean meshSupported;
  private final Map<Long, ChunkMesh> chunkMeshes = new ConcurrentHashMap<>();
  private long lastCleanupFrame = 0;

  public MeshShaderBackend() {
    this.meshSupported = NativeBridge.nSupportsMeshShaders();
    MetalLogger.info("[MetalRender] Mesh shader support: {}",
                     this.meshSupported ? "available"
                                        : "unavailable (compute fallback)");
  }

  public boolean isMeshEnabled() {
    boolean enabled =
        this.meshSupported && MetalRenderConfig.meshShadersEnabled();
    if (Math.random() < 0.001) {
      MetalLogger.info(
          "[MeshBackend] isMeshEnabled() = %s (supported=%s, config=%s)",
          enabled, this.meshSupported, MetalRenderConfig.meshShadersEnabled());
    }
    return enabled;
  }

  public void destroy() { this.chunkMeshes.clear(); }

  public void uploadBuildOutput(long nativeHandle, PersistentBufferArena arena,
                                ChunkBuildOutput output) {
    if (arena == null || output == null) {
      return;
    }

    RenderSection section = output.render;
    if (section == null) {
      return;
    }

    BlockPos origin = new BlockPos(section.getOriginX(), section.getOriginY(),
                                   section.getOriginZ());
    long key = origin.asLong();

    if (output.meshes == null || output.meshes.isEmpty()) {
      this.chunkMeshes.remove(key);
      return;
    }

    ChunkMesh mesh = new ChunkMesh(origin, MAX_LOD_LEVELS);

    for (Map.Entry<TerrainRenderPass, BuiltSectionMeshParts> entry :
         output.meshes.entrySet()) {
      BuiltSectionMeshParts parts = entry.getValue();
      if (parts == null || parts.getVertexData() == null ||
          parts.getVertexData().getLength() == 0) {
        continue;
      }

      ByteBuffer vanilla =
          parts.getVertexData().getDirectBuffer().duplicate().order(
              ByteOrder.LITTLE_ENDIAN);
      vanilla.clear();

      int vertexCount =
          parts.getVertexData().getLength() / VertexCompressor.INPUT_STRIDE;
      if (vertexCount <= 0) {
        continue;
      }

      CompressedMesh compressed =
          VertexCompressor.compress(origin, vanilla, vertexCount);
      if (compressed.vertexCount <= 0) {
        continue;
      }

      ByteBuffer persistent = arena.buffer();
      if (persistent == null) {
        MetalLogger.warn("[MetalRender] Persistent buffer unavailable; "
                             + "skipping chunk upload at {}",
                         origin);
        continue;
      }

      List<LevelData> levels = LODGenerator.generate(compressed);
      if (!MetalRenderConfig.meshShadersEnabled() && !levels.isEmpty()) {
        LevelData base = levels.get(0);
        levels = List.of(base);
      }
      if (levels.isEmpty()) {
        continue;
      }

      for (LevelData level : levels) {
        ByteBuffer levelBuffer = level.buffer();
        int bytes = levelBuffer.remaining();
        if (bytes <= 0) {
          continue;
        }

        int offset = arena.allocate(bytes);
        if (offset < 0) {
          MetalLogger.warn("[MetalRender] Persistent buffer exhausted while "
                               + "uploading chunk {}",
                           origin);
          break;
        }

        ByteBuffer target = persistent.duplicate();
        target.position(offset);
        target.limit(offset + bytes);
        ByteBuffer copy = levelBuffer.duplicate();
        target.put(copy);

        mesh.addDraw(level.level, new DrawCommand(offset, level.vertexCount));
      }
    }

    if (mesh.hasDraws()) {
      this.chunkMeshes.put(key, mesh);
    } else {
      this.chunkMeshes.remove(key);
    }
  }

  public void removeChunkMesh(BlockPos chunkPos) {
    if (chunkPos != null) {
      this.chunkMeshes.remove(chunkPos.asLong());
    }
  }

  private void cleanupDistantChunks(Camera camera) {
    if (this.chunkMeshes.size() < MAX_CHUNK_CACHE_SIZE) {
      return;
    }

    Vec3d cameraPos = camera.getPos();
    java.util.List<Long> toRemove = new java.util.ArrayList<>();

    for (ChunkMesh mesh : this.chunkMeshes.values()) {
      double distance = mesh.distanceTo(cameraPos);
      if (distance > MAX_CHUNK_DISTANCE) {
        toRemove.add(mesh.origin.asLong());
      }
    }

    for (Long key : toRemove) {
      this.chunkMeshes.remove(key);
    }

    if (!toRemove.isEmpty()) {
      MetalLogger.info(
          "[MeshBackend] Cleaned up {} distant chunks (cache size: {} -> {})",
          toRemove.size(), this.chunkMeshes.size() + toRemove.size(),
          this.chunkMeshes.size());
    }
  }

  public int emitDraws(long nativeHandle, RenderOptimizer optimizer,
                       Camera camera) {
    if (this.chunkMeshes.isEmpty()) {
      return 0;
    }

    RenderingMetrics.resetFrame();
    long currentFrame = System.nanoTime() / 16_666_666L;
    if (currentFrame - this.lastCleanupFrame > 60) {
      this.cleanupDistantChunks(camera);
      this.lastCleanupFrame = currentFrame;
    }

    Vec3d cameraPos = camera.getPos();
    boolean lodEnabled = MetalRenderConfig.distanceLodEnabled();
    int lodNear = MetalRenderConfig.lodDistanceThreshold();
    int lodFar = MetalRenderConfig.lodFarDistance();
    float distantScale = MetalRenderConfig.lodDistantScale();

    int commandIndex = 0;
    for (ChunkMesh mesh : this.chunkMeshes.values()) {
      if (!optimizer.shouldRenderChunk(mesh.origin, camera)) {
        continue;
      }

      double worldDistance = mesh.distanceTo(cameraPos);

      int lodLevel = 0;
      if (lodEnabled) {
        if (worldDistance > lodFar * 16.0) {
          lodLevel = Math.min(mesh.levelCount() - 1, 2);
        } else if (worldDistance > lodNear * 16.0) {
          lodLevel = Math.min(mesh.levelCount() - 1, 1);
        }
      }

      List<DrawCommand> draws = mesh.drawsForLevel(lodLevel);
      if (draws.isEmpty() && lodLevel > 0) {
        draws = mesh.drawsForLevel(lodLevel - 1);
      }
      if (draws.isEmpty()) {
        draws = mesh.drawsForLevel(0);
      }

      RenderingMetrics.recordLodUsage(lodLevel, 0, 0);

      boolean applyDistanceScale =
          lodEnabled && lodLevel == mesh.levelCount() - 1;
      for (DrawCommand draw : draws) {
        int vertexCount = draw.vertexCount;
        if (applyDistanceScale) {
          vertexCount = Math.max(3, (int)(vertexCount * distantScale));
        }

        RenderingMetrics.addVertices(vertexCount);
        RenderingMetrics.addDrawCommand();
        NativeBridge.nQueueIndirectDraw(nativeHandle, commandIndex++,
                                        draw.vertexOffset, 0L, vertexCount, 0,
                                        0, 1, 0, (float)worldDistance);
      }
    }

    return commandIndex;
  }

  private static final class ChunkMesh {
    final BlockPos origin;
    final LODLevel[] levels;

    ChunkMesh(BlockPos origin, int levelCount) {
      this.origin = origin;
      this.levels = new LODLevel[Math.max(1, levelCount)];
      for (int i = 0; i < this.levels.length; i++) {
        this.levels[i] = new LODLevel();
      }
    }

    void addDraw(int levelIndex, DrawCommand command) {
      this.level(levelIndex).add(command);
    }

    boolean hasDraws() {
      for (LODLevel level : this.levels) {
        if (!level.draws.isEmpty()) {
          return true;
        }
      }
      return false;
    }

    List<DrawCommand> drawsForLevel(int index) {
      return this.level(index).draws;
    }

    int levelCount() { return this.levels.length; }

    double distanceTo(Vec3d cameraPos) {
      double centerX = (double)this.origin.getX() + 8.0 - cameraPos.x;
      double centerY = (double)this.origin.getY() + 8.0 - cameraPos.y;
      double centerZ = (double)this.origin.getZ() + 8.0 - cameraPos.z;
      return Math.sqrt(centerX * centerX + centerY * centerY +
                       centerZ * centerZ);
    }

    private LODLevel level(int index) {
      int clamped = Math.max(0, Math.min(index, this.levels.length - 1));
      return this.levels[clamped];
    }
  }

  private static final class LODLevel {
    final List<DrawCommand> draws = new CopyOnWriteArrayList<>();

    void add(DrawCommand command) { this.draws.add(command); }
  }

  private static final class DrawCommand {
    final int vertexOffset;
    final int vertexCount;

    DrawCommand(int vertexOffset, int vertexCount) {
      this.vertexOffset = vertexOffset;
      this.vertexCount = vertexCount;
    }
  }
}
