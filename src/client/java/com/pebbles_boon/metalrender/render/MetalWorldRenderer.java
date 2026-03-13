package com.pebbles_boon.metalrender.render;

import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.backend.MetalRenderer;
import com.pebbles_boon.metalrender.culling.FrustumCuller;
import com.pebbles_boon.metalrender.entity.MetalEntityRenderer;
import com.pebbles_boon.metalrender.nativebridge.NativeBridge;
import com.pebbles_boon.metalrender.render.chunk.CustomChunkMesher;
import com.pebbles_boon.metalrender.render.chunk.MetalChunkContext;
import com.pebbles_boon.metalrender.util.MetalLogger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;
import org.joml.Matrix4f;
import org.joml.Vector3f;

public class MetalWorldRenderer {

  private static MetalWorldRenderer instance;

  private final FrustumCuller frustumCuller;
  private final MetalEntityRenderer entityRenderer;
  private final CustomChunkMesher chunkMesher;
  private final MetalTextureManager textureManager;
  private final IOSurfaceBlitter ioSurfaceBlitter;
  private final Matrix4f projectionMatrix;
  private final Matrix4f modelViewMatrix;
  private boolean worldLoaded;
  private boolean renderingActive;
  private boolean texturesReady;
  private int frameCount;
  private int maxMeshes = 131072;
  private int maxDrawnChunksPerFrame = 65536;
  private final Set<Long> pendingChunkRebuilds = new HashSet<>();
  private final List<long[]> pendingSectionKeys = new ArrayList<>();
  private int lastDrawnChunkCount;
  private long lastDiagLogMs;

  public MetalWorldRenderer() {
    this.frustumCuller = new FrustumCuller();
    this.entityRenderer = new MetalEntityRenderer();
    this.chunkMesher = new CustomChunkMesher();
    MetalRenderer renderer = MetalRenderClient.getRenderer();
    long device =
        renderer != null ? renderer.getBackend().getDeviceHandle() : 0;
    this.textureManager = new MetalTextureManager(device);
    this.ioSurfaceBlitter = new IOSurfaceBlitter();
    this.projectionMatrix = new Matrix4f();
    this.modelViewMatrix = new Matrix4f();
    instance = this;
  }

  public static MetalWorldRenderer getInstance() { return instance; }

  public void onWorldLoad() {
    worldLoaded = true;
    MetalRenderer renderer = MetalRenderClient.getRenderer();
    if (renderer != null && renderer.isAvailable()) {
      MinecraftClient client = MinecraftClient.getInstance();
      int w = client.getWindow().getFramebufferWidth();
      int h = client.getWindow().getFramebufferHeight();
      if (w > 0 && h > 0) {
        renderer.resize(w, h);
      }
      chunkMesher.initialize(renderer.getBackend().getDeviceHandle());
      entityRenderer.setDeviceAndPipeline(
          renderer.getBackend().getDeviceHandle(), 0);
      renderingActive = true;
      entityRenderer.setActive(true);
      texturesReady = false;
      MetalLogger.info("Metal world rendering activated (" + w + "x" + h + ")");
    }
  }

  public void onWorldUnload() {
    worldLoaded = false;
    renderingActive = false;
    texturesReady = false;
    entityRenderer.shutdown();
    textureManager.destroy();
    ioSurfaceBlitter.destroy();
    chunkMesher.clear();
    pendingChunkRebuilds.clear();
    pendingSectionKeys.clear();
    frameCount = 0;
    lastDrawnChunkCount = 0;
  }

  public boolean shouldRenderWithMetal() {
    return worldLoaded && renderingActive &&
        MetalRenderClient.isMetalAvailable() &&
        MetalRenderClient.getConfig().enableMetalRendering;
  }

  public void prepareMeshes() {
    MetalRenderer renderer = MetalRenderClient.getRenderer();
    if (renderer == null || !renderer.isAvailable())
      return;

    MinecraftClient client = MinecraftClient.getInstance();
    int w = client.getWindow().getFramebufferWidth();
    int h = client.getWindow().getFramebufferHeight();
    renderer.resize(w, h);

    if (!texturesReady && frameCount > 2) {
      textureManager.loadBlockAtlas();
      textureManager.loadLightmap();
      texturesReady = textureManager.isBlockAtlasLoaded() &&
                      textureManager.isLightmapLoaded();
    } else if (texturesReady && textureManager.isUsingFallbackBlockAtlas() &&
               frameCount % 120 == 0) {
      textureManager.loadBlockAtlas();
    }

    long now = System.currentTimeMillis();
    if (now - lastDiagLogMs > 5000) {
      lastDiagLogMs = now;
      MetalLogger.info(
          "DiagWorld: texturesReady=" + texturesReady +
          ", atlasFallback=" + textureManager.isUsingFallbackBlockAtlas() +
          ", meshCount=" + chunkMesher.getMeshCount());
    }

    Camera camera = client.gameRenderer.getCamera();
    Vector3f camPos = new Vector3f((float)camera.getCameraPos().x,
                                   (float)camera.getCameraPos().y,
                                   (float)camera.getCameraPos().z);

    if (MetalRenderClient.getConfig().mesherMode == 0) {
      pruneFarMeshes(client, camPos);
      buildPendingChunkMeshes(client);
    }
  }

  public void beginFrame(Camera camera, float tickDelta, Matrix4f projection,
                         Matrix4f modelView) {
    MetalRenderer renderer = MetalRenderClient.getRenderer();
    if (renderer == null || !renderer.isAvailable())
      return;

    projectionMatrix.set(projection);
    modelViewMatrix.set(modelView);

    Vector3f camPos = new Vector3f((float)camera.getCameraPos().x,
                                   (float)camera.getCameraPos().y,
                                   (float)camera.getCameraPos().z);
    frustumCuller.update(projectionMatrix, modelViewMatrix, camPos);

    lastDrawnChunkCount = 0;
    renderer.beginFrame(tickDelta);
    Matrix4f metalProj = new Matrix4f(projectionMatrix);
    metalProj.m02(0.5f * metalProj.m02() + 0.5f * metalProj.m03());
    metalProj.m12(0.5f * metalProj.m12() + 0.5f * metalProj.m13());
    metalProj.m22(0.5f * metalProj.m22() + 0.5f * metalProj.m23());
    metalProj.m32(0.5f * metalProj.m32() + 0.5f * metalProj.m33());

    renderer.setProjectionMatrix(metalProj);
    renderer.setModelViewMatrix(modelViewMatrix);
    renderer.setCameraPosition(camera.getCameraPos().x, camera.getCameraPos().y,
                               camera.getCameraPos().z);

    if (texturesReady) {
      long blockAtlas = textureManager.getBlockAtlasTexture();
      if (blockAtlas != 0) {
        renderer.bindTexture(blockAtlas, 0);
      }
      long lightmap = textureManager.getLightmapTexture();
      if (lightmap != 0) {
        renderer.bindTexture(lightmap, 1);
      }
    }

    long frameCtx = renderer.getCurrentFrameContext();
    if (frameCtx != 0) {
      if (MetalRenderClient.getConfig().mesherMode == 0) {

        long inhousePipeline = renderer.getBackend().getInhousePipelineHandle();
        if (inhousePipeline != 0) {
          NativeBridge.nSetPipelineState(frameCtx, inhousePipeline);
        }

        int drawn = 0;
        for (CustomChunkMesher.ChunkMeshData mesh :
             chunkMesher.getAllMeshes()) {
          if (mesh.bufferHandle == 0 || mesh.quadCount <= 0)
            continue;

          float cx = mesh.chunkX * 16.0f - camPos.x;
          float cy = mesh.chunkY * 16.0f - camPos.y;
          float cz = mesh.chunkZ * 16.0f - camPos.z;

          if (!frustumCuller.testBoundingBox(cx, cy, cz, cx + 16, cy + 16,
                                             cz + 16))
            continue;

          NativeBridge.nSetChunkOffset(frameCtx, cx, cy, cz);
          NativeBridge.nDrawIndexedBuffer(frameCtx, mesh.bufferHandle,
                                          chunkMesher.getGlobalIndexBuffer(),
                                          mesh.quadCount * 6, 0);
          drawn++;
        }
        lastDrawnChunkCount = drawn;
        if (frameCount % 1000 == 0) {
          MetalLogger.info("Frame " + frameCount + ": drew " + drawn +
                           " chunks");
        }
      }
    }
  }

  public void endFrame() {
    MetalRenderer renderer = MetalRenderClient.getRenderer();
    if (renderer == null || !renderer.isAvailable())
      return;

    long frameCtx = renderer.getCurrentFrameContext();
    if (frameCtx != 0) {

      boolean terrainActive =
          renderer.getBackend() != null &&
          renderer.getBackend().getDefaultPipelineHandle() != 0;
      entityRenderer.renderCapturedEntities(frameCtx);
    }

    renderer.endFrame();
    frameCount++;
  }

  private void buildPendingChunkMeshes(MinecraftClient client) {
    ClientWorld world = client.world;
    if (world == null)
      return;

    if (chunkMesher.getMeshCount() >= maxMeshes)
      return;

    int renderDist = client.options.getViewDistance().getValue();
    if (client.player == null)
      return;

    int playerChunkX = client.player.getChunkPos().x;
    int playerChunkZ = client.player.getChunkPos().z;

    for (int ring = 0; ring <= renderDist; ring++) {
      int startDx = -ring, endDx = ring;
      int startDz = -ring, endDz = ring;

      for (int dx = startDx; dx <= endDx; dx++) {
        for (int dz = startDz; dz <= endDz; dz++) {

          if (ring > 0 && Math.abs(dx) < ring && Math.abs(dz) < ring)
            continue;

          int cx = playerChunkX + dx;
          int cz = playerChunkZ + dz;

          int chunkDist = Math.max(Math.abs(dx), Math.abs(dz));
          int lodLevel =
              com.pebbles_boon.metalrender.config.MetalRenderConfig.getLodLevel(
                  chunkDist);

          WorldChunk chunk = world.getChunkManager().getWorldChunk(cx, cz);
          if (chunk == null)
            continue;

          ChunkSection[] sections = chunk.getSectionArray();
          for (int sy = 0; sy < sections.length; sy++) {
            ChunkSection section = sections[sy];
            if (section == null || section.isEmpty())
              continue;

            int worldY = chunk.sectionIndexToCoord(sy);

            if (chunkMesher.needsLodRebuild(cx, worldY, cz, lodLevel)) {
              chunkMesher.markDirty(cx, worldY, cz);
            }

            if (chunkMesher.hasMesh(cx, worldY, cz))
              continue;

            if (chunkMesher.getMeshCount() >= maxMeshes)
              return;

            chunkMesher.buildMeshFromWorld(cx, worldY, cz, lodLevel);
          }
        }
      }
    }
  }

  public int getGLTextureForCompositing() {
    MetalRenderer renderer = MetalRenderClient.getRenderer();
    if (renderer == null)
      return 0;
    return renderer.getGLTextureId();
  }

  public FrustumCuller getFrustumCuller() { return frustumCuller; }

  public MetalEntityRenderer getEntityRenderer() { return entityRenderer; }

  public CustomChunkMesher getChunkMesher() { return chunkMesher; }

  public void setLastDrawnChunkCount(int c) { this.lastDrawnChunkCount = c; }

  public void addDrawnChunkCount(int c) { this.lastDrawnChunkCount += c; }

  public int getLastDrawnChunkCount() { return lastDrawnChunkCount; }

  public boolean areTexturesReady() { return texturesReady; }

  public MetalTextureManager getTextureManager() { return textureManager; }

  public boolean isWorldLoaded() { return worldLoaded; }

  public int getFrameCount() { return frameCount; }

  private void pruneFarMeshes(MinecraftClient client,
                              org.joml.Vector3f camPos) {
    if (client.player == null)
      return;
    int renderDist = client.options.getViewDistance().getValue();
    float maxDist = (renderDist + 2) * 16.0f;
    float maxDistSq = maxDist * maxDist;

    var iter = chunkMesher.getAllMeshes().iterator();
    while (iter.hasNext()) {
      CustomChunkMesher.ChunkMeshData mesh = iter.next();
      float dx = mesh.chunkX * 16.0f + 8.0f - camPos.x;
      float dz = mesh.chunkZ * 16.0f + 8.0f - camPos.z;
      if (dx * dx + dz * dz > maxDistSq) {
        chunkMesher.removeMesh(mesh.chunkX, mesh.chunkY, mesh.chunkZ);
      }
    }
  }

  public static boolean shouldBlitAt(String timingPoint) {

    return "flip_head".equals(timingPoint);
  }

  public static String getBlitTimingMode() { return "flip_head"; }

  public void forceBlitNow() {
    MetalRenderer renderer = MetalRenderClient.getRenderer();
    if (renderer == null || !renderer.isAvailable())
      return;
    long handle = renderer.getHandle();
    if (handle == 0)
      return;
    ioSurfaceBlitter.blit(handle);
  }

  public boolean isReady() { return worldLoaded && renderingActive; }

  public void renderFrame(Object viewport, Object matrices, double x, double y,
                          double z) {}

  public void scheduleSectionRebuild(int blockX, int blockY, int blockZ) {
    if (!worldLoaded || !renderingActive) {
      MetalLogger.info(
          "[scheduleSectionRebuild] Skipped: worldLoaded=%b renderingActive=%b",
          worldLoaded, renderingActive);
      return;
    }

    int cx = blockX >> 4;
    int cy = blockY >> 4;
    int cz = blockZ >> 4;

    MetalLogger.info("[scheduleSectionRebuild] Marking dirty section "
                         + "[%d,%d,%d] for block at (%d,%d,%d)",
                     cx, cy, cz, blockX, blockY, blockZ);

    chunkMesher.markDirty(cx, cy, cz);

    int localX = blockX & 15;
    int localY = blockY & 15;
    int localZ = blockZ & 15;
    if (localX == 0)
      chunkMesher.markDirty(cx - 1, cy, cz);
    if (localX == 15)
      chunkMesher.markDirty(cx + 1, cy, cz);
    if (localY == 0)
      chunkMesher.markDirty(cx, cy - 1, cz);
    if (localY == 15)
      chunkMesher.markDirty(cx, cy + 1, cz);
    if (localZ == 0)
      chunkMesher.markDirty(cx, cy, cz - 1);
    if (localZ == 15)
      chunkMesher.markDirty(cx, cy, cz + 1);
  }
}
