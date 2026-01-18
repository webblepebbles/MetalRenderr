package com.metalrender.sodium.backend;

import com.metalrender.config.MetalRenderConfig;
import com.metalrender.lod.LODGenerator;
import com.metalrender.lod.LODGenerator.LevelData;
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

  public int getMeshCount() {
    return this.chunkMeshes.size();
  }

  public boolean isMeshEnabled() {
    boolean enabled = this.meshSupported && MetalRenderConfig.meshShadersEnabled();
    if (Math.random() < 0.001) {
      MetalLogger.info(
          "[MeshBackend] isMeshEnabled() = %s (supported=%s, config=%s)",
          enabled, this.meshSupported, MetalRenderConfig.meshShadersEnabled());
    }
    return enabled;
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
      MetalLogger.info("[MeshBackend] uploadBuildOutput: no meshes for chunk at %s", origin);
      ChunkMesh old = this.chunkMeshes.remove(key);
      if (old != null)
        freeMesh(old, arena);
      return;
    }

    MetalLogger.info("[MeshBackend] uploadBuildOutput: processing %d mesh entries for chunk at %s",
        output.meshes.size(), origin);

    ChunkMesh mesh = new ChunkMesh(origin, MAX_LOD_LEVELS);

    for (Map.Entry<TerrainRenderPass, BuiltSectionMeshParts> entry : output.meshes.entrySet()) {
      BuiltSectionMeshParts parts = entry.getValue();
      if (parts == null || parts.getVertexData() == null ||
          parts.getVertexData().getLength() == 0) {
        continue;
      }

      ByteBuffer vanilla = parts.getVertexData().getDirectBuffer().duplicate().order(
          ByteOrder.LITTLE_ENDIAN);
      vanilla.clear();

      int vertexCount = parts.getVertexData().getLength() / VertexPassthrough.SODIUM_STRIDE;
      if (vertexCount <= 0) {
        continue;
      }

      // Simple passthrough - just copy raw Sodium data with quad-to-triangle
      // conversion
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

      // Single LOD level - just copy the data directly
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

      mesh.addDraw(0, new DrawCommand(offset, passthrough.vertexCount, bytes));
    }

    if (mesh.hasDraws()) {
      ChunkMesh old = this.chunkMeshes.put(key, mesh);
      if (old != null)
        freeMesh(old, arena);
      // Debug: log when mesh is added
      if (this.chunkMeshes.size() % 10 == 1) {
        MetalLogger.info("[MeshBackend] Added mesh for chunk at {}, total meshes: {}",
            origin, this.chunkMeshes.size());
      }
    } else {
      ChunkMesh old = this.chunkMeshes.remove(key);
      if (old != null)
        freeMesh(old, arena);
    }
  }

  public void removeChunkMesh(BlockPos chunkPos, PersistentBufferArena arena) {
    if (chunkPos != null) {
      ChunkMesh old = this.chunkMeshes.remove(chunkPos.asLong());
      if (old != null)
        freeMesh(old, arena);
    }
  }

  private void freeMesh(ChunkMesh mesh, PersistentBufferArena arena) {
    if (mesh == null || arena == null)
      return;
    for (int i = 0; i < mesh.levelCount(); i++) {
      for (DrawCommand cmd : mesh.drawsForLevel(i)) {
        arena.free(cmd.vertexOffset, cmd.allocatedBytes);
      }
    }
  }

  private void cleanupDistantChunks(Camera camera, PersistentBufferArena arena) {
    if (this.chunkMeshes.size() < MAX_CHUNK_CACHE_SIZE) {
      return;
    }

    Vec3d cameraPos = camera.getCameraPos();
    java.util.List<Long> toRemove = new java.util.ArrayList<>();

    for (ChunkMesh mesh : this.chunkMeshes.values()) {
      double distance = mesh.distanceTo(cameraPos);
      if (distance > MAX_CHUNK_DISTANCE) {
        toRemove.add(mesh.origin.asLong());
      }
    }

    for (Long key : toRemove) {
      ChunkMesh old = this.chunkMeshes.remove(key);
      if (old != null)
        freeMesh(old, arena);
    }

    if (!toRemove.isEmpty()) {
      MetalLogger.info(
          "[MeshBackend] Cleaned up {} distant chunks (cache size: {} -> {})",
          toRemove.size(), this.chunkMeshes.size() + toRemove.size(),
          this.chunkMeshes.size());
    }
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

    Vec3d cameraPos = camera.getCameraPos();
    boolean lodEnabled = MetalRenderConfig.distanceLodEnabled();
    int lodNear = MetalRenderConfig.lodDistanceThreshold();
    int lodFar = MetalRenderConfig.lodFarDistance();
    float distantScale = MetalRenderConfig.lodDistantScale();

    int commandIndex = 0;
    int skippedByOptimizer = 0;
    int emptyDraws = 0;
    for (ChunkMesh mesh : this.chunkMeshes.values()) {
      if (!optimizer.shouldRenderChunk(mesh.origin, camera)) {
        skippedByOptimizer++;
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

      if (draws.isEmpty()) {
        emptyDraws++;
        continue;
      }

      RenderingMetrics.recordLodUsage(lodLevel, 0, 0);

      boolean applyDistanceScale = lodEnabled && lodLevel == mesh.levelCount() - 1;
      for (DrawCommand draw : draws) {
        int vertexCount = draw.vertexCount;
        if (applyDistanceScale) {
          vertexCount = Math.max(3, (int) (vertexCount * distantScale));
        }

        RenderingMetrics.addVertices(vertexCount);
        RenderingMetrics.addDrawCommand();

        // Pass chunk origin (the shader will subtract cameraPos)
        NativeBridge.nQueueIndirectDraw(nativeHandle, commandIndex++,
            draw.vertexOffset, 0L, vertexCount,
            mesh.origin.getX(),
            mesh.origin.getY(),
            mesh.origin.getZ(),
            0, (float) worldDistance);
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

    int levelCount() {
      return this.levels.length;
    }

    double distanceTo(Vec3d cameraPos) {
      double centerX = (double) this.origin.getX() + 8.0 - cameraPos.x;
      double centerY = (double) this.origin.getY() + 8.0 - cameraPos.y;
      double centerZ = (double) this.origin.getZ() + 8.0 - cameraPos.z;
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

    void add(DrawCommand command) {
      this.draws.add(command);
    }
  }

  private static final class DrawCommand {
    final int vertexOffset;
    final int vertexCount;
    final int allocatedBytes; // Track exact allocation size for freeing

    DrawCommand(int vertexOffset, int vertexCount, int allocatedBytes) {
      this.vertexOffset = vertexOffset;
      this.vertexCount = vertexCount;
      this.allocatedBytes = allocatedBytes;
    }
  }
}
