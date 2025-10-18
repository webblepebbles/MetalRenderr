package com.metalrender.sodium.backend;

import com.metalrender.config.MetalRenderConfig;
import com.metalrender.lod.LODGenerator;
import com.metalrender.lod.LODGenerator.LevelData;
import com.metalrender.nativebridge.NativeBridge;
import com.metalrender.performance.RenderOptimizer;
import com.metalrender.performance.RenderingMetrics;
import com.metalrender.util.MetalLogger;
import com.metalrender.util.ObjectPool;
import com.metalrender.util.PersistentBufferArena;
import com.metalrender.util.VertexCompressor;
import com.metalrender.util.VertexCompressor.CompressedMesh;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
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
  private static final int MAX_LOD_LEVELS = 5;
  private static final int MAX_CHUNK_CACHE_SIZE = 7192;
  private static final double MAX_CHUNK_DISTANCE =
      Double.MAX_VALUE; 

  private final boolean meshSupported;
  private final Map<Long, ChunkMesh> chunkMeshes = new ConcurrentHashMap<>();
  private long lastCleanupFrame = 0;
  private final ObjectPool<ArrayList<DrawCommand>> drawListPool =
      new ObjectPool<>(() -> new ArrayList<>(8), 256);

  public MeshShaderBackend() {
    this.meshSupported = NativeBridge.nSupportsMeshShaders();
    MetalLogger.info("[MetalRender] Mesh shader support: {}",
                     this.meshSupported ? "available"
                                        : "unavailable (compute fallback)");
  }

  public boolean isMeshEnabled() {
    return this.meshSupported && MetalRenderConfig.meshShadersEnabled();
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

    if (currentFrame - this.lastCleanupFrame > 5) {
      this.cleanupDistantChunks(camera);
      this.lastCleanupFrame = currentFrame;
    }
    Vec3d cameraPos = camera.getPos();
    boolean lodEnabled = MetalRenderConfig.distanceLodEnabled();
    int lodNear = MetalRenderConfig.lodDistanceThreshold();
    int lodFar = MetalRenderConfig.lodFarDistance();

    double lodThreshold1 = lodNear * 3.0;
    double lodThreshold2 = lodFar * 4.0;
    double lodThreshold3 = lodFar * 8.0;
    double lodThreshold4 = lodFar * 14.0;
    double distanceScaleThreshold = lodFar * 24.0;
    double maxRenderDistSq = 384.0 * 384.0;
    double cameraX = cameraPos.x;
    double cameraY = cameraPos.y;
    double cameraZ = cameraPos.z;

    double lodThreshold1Sq = lodThreshold1 * lodThreshold1;
    double lodThreshold2Sq = lodThreshold2 * lodThreshold2;
    double lodThreshold3Sq = lodThreshold3 * lodThreshold3;

    int commandIndex = 0;
    for (ChunkMesh mesh : this.chunkMeshes.values()) {
      double dx = mesh.origin.getX() + 8.0 - cameraX;
      double dy = mesh.origin.getY() + 8.0 - cameraY;
      double dz = mesh.origin.getZ() + 8.0 - cameraZ;
      double distSq = dx * dx + dy * dy + dz * dz;

      if (!optimizer.shouldRenderChunk(mesh.origin, camera)) {
        continue;
      }


      int lodLevel = 0;
      boolean needsActualDistance = false;
      if (lodEnabled && distSq > lodThreshold1Sq) {
        if (distSq > (lodThreshold4 * lodThreshold4)) {
          lodLevel = 4; 
        } else if (distSq > lodThreshold3Sq) {
          lodLevel = 3;
        } else if (distSq > lodThreshold2Sq) {
          lodLevel = 2;
        } else {
          lodLevel = 1;
        }
        if (lodLevel >= mesh.levelCount()) {
          lodLevel = mesh.levelCount() - 1;
        }
        needsActualDistance =
            distSq > (distanceScaleThreshold * distanceScaleThreshold);
      }
      double worldDistance = needsActualDistance ? Math.sqrt(distSq) : 0.0;

      List<DrawCommand> draws = mesh.drawsForLevel(lodLevel);
      if (draws.isEmpty() && lodLevel > 0) {
        draws = mesh.drawsForLevel(lodLevel - 1);
      }
      if (draws.isEmpty()) {
        draws = mesh.drawsForLevel(0);
      }

      if (draws.isEmpty()) {
        continue;
      }

      RenderingMetrics.recordLodUsage(lodLevel, 0, 0);

      boolean applyDistanceScale = needsActualDistance;

      for (DrawCommand draw : draws) {
        int vertexCount = draw.vertexCount;
        if (applyDistanceScale) {
          vertexCount =
              (vertexCount * 3) >> 2; 
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
    private double cachedDistance = -1.0;
    private Vec3d lastCameraPos = null;

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
      if (lastCameraPos != cameraPos) {
        double centerX = (double)this.origin.getX() + 8.0 - cameraPos.x;
        double centerY = (double)this.origin.getY() + 8.0 - cameraPos.y;
        double centerZ = (double)this.origin.getZ() + 8.0 - cameraPos.z;
        this.cachedDistance = Math.sqrt(centerX * centerX + centerY * centerY +
                                        centerZ * centerZ);
        this.lastCameraPos = cameraPos;
      }
      return this.cachedDistance;
    }

    private LODLevel level(int index) {
      int clamped = Math.max(0, Math.min(index, this.levels.length - 1));
      return this.levels[clamped];
    }
  }

  private static final class LODLevel {
    final List<DrawCommand> draws = new ArrayList<>();

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
