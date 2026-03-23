package com.pebbles_boon.metalrender.render;
import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.backend.MetalRenderer;
import com.pebbles_boon.metalrender.culling.FrustumCuller;
import com.pebbles_boon.metalrender.entity.MetalEntityRenderer;
import com.pebbles_boon.metalrender.nativebridge.MetalHardwareChecker;
import com.pebbles_boon.metalrender.nativebridge.NativeBridge;
import com.pebbles_boon.metalrender.nativebridge.NativeMemory;
import com.pebbles_boon.metalrender.particle.MetalParticleRenderer;
import com.pebbles_boon.metalrender.render.chunk.CustomChunkMesher;
import com.pebbles_boon.metalrender.render.chunk.MetalChunkContext;
import com.pebbles_boon.metalrender.sodium.backend.MeshShaderBackend;
import com.pebbles_boon.metalrender.util.MetalLogger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;
import org.joml.Matrix4f;
import org.joml.Vector3f;
public class MetalWorldRenderer {
  private static MetalWorldRenderer instance;
  private final FrustumCuller frustumCuller;
  private final MetalEntityRenderer entityRenderer;
  private final MetalParticleRenderer particleRenderer;
  private final CustomChunkMesher chunkMesher;
  private final MetalTextureManager textureManager;
  private final IOSurfaceBlitter ioSurfaceBlitter;
  private final Matrix4f projectionMatrix;
  private final Matrix4f modelViewMatrix;
  private boolean worldLoaded;
  private boolean renderingActive;
  private boolean texturesReady;
  private int frameCount;
  private int maxMeshes = 65536;
  private int maxDrawnChunksPerFrame = 65536;
  private final Set<Long> pendingChunkRebuilds = new HashSet<>();
  private final List<long[]> pendingSectionKeys = new ArrayList<>();
  private int lastDrawnChunkCount;
  private long lastDiagLogMs;
  private long outlineBufferHandle;
  private long jPruneAcc = 0, jBuildAcc = 0, jLodAcc = 0;
  private int jProfCount = 0;
  private float[] batchDrawData;
  private float[] batchPackedData;
  private final float[] sortTmp = new float[7];
  private boolean gpuDrivenEnabled;
  private MeshShaderBackend meshShaderBackend;
  private ByteBuffer subChunkUploadBuffer;
  private ByteBuffer chunkUniformsBuffer;
  private int subChunkUploadCapacity = 4096;
  private final float[] viewProjMatrix = new float[16];
  private final float[] projMatrixFlat = new float[16];
  private final float[] modelViewFlat = new float[16];
  private final float[] cameraPosFloat = new float[4];
  private final float[] frustumPlanesFlat = new float[24]; 
  private final int[] gpuCullStats = new int[5];
  private int lastGPUVisibleCount;
  private long lastThermalLogMs;
  public MetalWorldRenderer() {
    this.frustumCuller = new FrustumCuller();
    this.entityRenderer = new MetalEntityRenderer();
    this.particleRenderer = new MetalParticleRenderer();
    this.chunkMesher = new CustomChunkMesher();
    MetalRenderer renderer = MetalRenderClient.getRenderer();
    long device = renderer != null ? renderer.getBackend().getDeviceHandle() : 0;
    this.textureManager = new MetalTextureManager(device);
    this.ioSurfaceBlitter = new IOSurfaceBlitter();
    this.projectionMatrix = new Matrix4f();
    this.modelViewMatrix = new Matrix4f();
    instance = this;
  }
  public static MetalWorldRenderer getInstance() {
    return instance;
  }
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
      particleRenderer.setDeviceAndPipeline(
          renderer.getBackend().getDeviceHandle());
      renderingActive = true;
      entityRenderer.setActive(true);
      particleRenderer.setActive(true);
      texturesReady = false;
      long handle = renderer.getBackend().getDeviceHandle();
      meshShaderBackend = new MeshShaderBackend();
      meshShaderBackend.initialize();
      boolean meshShadersSupported = MetalHardwareChecker.supportsMeshShaders();
      gpuDrivenEnabled = true;
      if (gpuDrivenEnabled && handle != 0) {
        NativeBridge.nSetGPUDrivenEnabled(handle, true);
        subChunkUploadBuffer = ByteBuffer.allocateDirect(subChunkUploadCapacity * 48)
            .order(ByteOrder.nativeOrder());
        chunkUniformsBuffer = ByteBuffer.allocateDirect(subChunkUploadCapacity * 16)
            .order(ByteOrder.nativeOrder());
        MetalLogger.info("GPU-driven pipeline enabled (mesh shaders: %s)",
            meshShadersSupported ? "active" : "fallback");
      }
      MetalLogger.info("Metal world rendering activated (" + w + "x" + h + ")");
    }
  }
  public void onWorldUnload() {
    worldLoaded = false;
    renderingActive = false;
    texturesReady = false;
    entityRenderer.shutdown();
    particleRenderer.shutdown();
    textureManager.destroy();
    ioSurfaceBlitter.destroy();
    chunkMesher.clear();
    pendingChunkRebuilds.clear();
    pendingSectionKeys.clear();
    frameCount = 0;
    lastDrawnChunkCount = 0;
    if (meshShaderBackend != null) {
      meshShaderBackend.shutdown();
      meshShaderBackend = null;
    }
    gpuDrivenEnabled = false;
    subChunkUploadBuffer = null;
    chunkUniformsBuffer = null;
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
    } else if (texturesReady && !textureManager.isUsingFallbackBlockAtlas()) {
      if (frameCount % 4 == 0) {
        textureManager.updateBlockAtlas();
      }
    }
    long now = System.currentTimeMillis();
    long diagInterval = chunkMesher.getMeshCount() < 2000 ? 1000 : 5000;
    if (now - lastDiagLogMs > diagInterval) {
      lastDiagLogMs = now;
      MetalLogger.info(
          "DiagWorld: texturesReady=" + texturesReady +
              ", atlasFallback=" + textureManager.isUsingFallbackBlockAtlas() +
              ", meshCount=" + chunkMesher.getMeshCount());
    }
    Camera camera = client.gameRenderer.getCamera();
    Vector3f camPos = new Vector3f((float) camera.getCameraPos().x,
        (float) camera.getCameraPos().y,
        (float) camera.getCameraPos().z);
    if (MetalRenderClient.getConfig().enableMetalRendering) {
      long t0 = System.nanoTime();
      boolean nearMeshLimit = chunkMesher.getMeshCount() >= maxMeshes - 500;
      if (frameCount % 30 == 0 || (nearMeshLimit && !pendingBuildSet.isEmpty())) {
        pruneFarMeshes(client, camPos);
      }
      long t1 = System.nanoTime();
      buildPendingChunkMeshes(client);
      long t2 = System.nanoTime();
      if (frameCount % 5 == 0) {
        rebuildLodMeshes(client);
      }
      long t3 = System.nanoTime();
      jPruneAcc += (t1 - t0);
      jBuildAcc += (t2 - t1);
      jLodAcc += (t3 - t2);
      jProfCount++;
      if (jProfCount >= 120) {
        double pruneMs = jPruneAcc / 1e6 / jProfCount;
        double buildMs = jBuildAcc / 1e6 / jProfCount;
        double lodMs = jLodAcc / 1e6 / jProfCount;
        MetalLogger.info("JAVA_PROFILE: prune=%.2fms build=%.2fms lod=%.2fms (avg/%d) pending=%d queued=%d meshes=%d",
            pruneMs, buildMs, lodMs, jProfCount, pendingBuildSet.size(), chunkMesher.getPendingCount(),
            chunkMesher.getMeshCount());
        jPruneAcc = 0;
        jBuildAcc = 0;
        jLodAcc = 0;
        jProfCount = 0;
      }
    }
  }
  private double prevCamX = Double.NaN, prevCamY, prevCamZ;
  private float prevCamYaw = Float.NaN, prevCamPitch;
  private int staticFrameCount = 0;
  private static final int MAX_STATIC_FRAMES = 10; 
  private boolean isCameraStatic(Camera camera) {
    double cx = camera.getCameraPos().x;
    double cy = camera.getCameraPos().y;
    double cz = camera.getCameraPos().z;
    float yaw = camera.getYaw();
    float pitch = camera.getPitch();
    boolean isStatic = Math.abs(cx - prevCamX) < 0.01 &&
        Math.abs(cy - prevCamY) < 0.01 &&
        Math.abs(cz - prevCamZ) < 0.01 &&
        Math.abs(yaw - prevCamYaw) < 0.1f &&
        Math.abs(pitch - prevCamPitch) < 0.1f;
    prevCamX = cx;
    prevCamY = cy;
    prevCamZ = cz;
    prevCamYaw = yaw;
    prevCamPitch = pitch;
    return isStatic;
  }
  public void beginFrame(Camera camera, float tickDelta, Matrix4f projection,
      Matrix4f modelView) {
    MetalRenderer renderer = MetalRenderClient.getRenderer();
    if (renderer == null || !renderer.isAvailable())
      return;
    projectionMatrix.set(projection);
    modelViewMatrix.set(modelView);
    Vector3f camPos = new Vector3f((float) camera.getCameraPos().x,
        (float) camera.getCameraPos().y,
        (float) camera.getCameraPos().z);
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
    boolean cameraStatic = isCameraStatic(camera);
    boolean skipTerrainDraw = false;
    if (cameraStatic) {
      staticFrameCount++;
      if (staticFrameCount < MAX_STATIC_FRAMES) {
        skipTerrainDraw = true;
        NativeBridge.nSetReuseTerrainFrame(true); 
      } else {
        staticFrameCount = 0; 
      }
    } else {
      staticFrameCount = 0;
    }
    long frameCtx = renderer.getCurrentFrameContext();
    if (frameCtx != 0) {
      if (MetalRenderClient.getConfig().enableMetalRendering) {
        long inhousePipeline = renderer.getBackend().getInhousePipelineHandle();
        if (inhousePipeline != 0) {
          NativeBridge.nSetPipelineState(frameCtx, inhousePipeline);
        }
        if (!skipTerrainDraw) {
          long ibHandle = chunkMesher.getGlobalIndexBuffer();
          if (ibHandle != 0) {
            int drawn = NativeBridge.nDrawAllVisibleChunks(frameCtx, ibHandle);
            lastDrawnChunkCount = drawn;
            if (frameCount < 10 || frameCount % 1000 == 0) {
              MetalLogger.info("Frame %d: V18 native drew %d chunks", frameCount, drawn);
            }
          } else {
            lastDrawnChunkCount = 0;
          }
        }
      }
    }
  }
  private static void extractFrustumPlanes(Matrix4f vp, float[] out) {
    out[0] = vp.m03() + vp.m00();
    out[1] = vp.m13() + vp.m10();
    out[2] = vp.m23() + vp.m20();
    out[3] = vp.m33() + vp.m30();
    normalizePlane(out, 0);
    out[4] = vp.m03() - vp.m00();
    out[5] = vp.m13() - vp.m10();
    out[6] = vp.m23() - vp.m20();
    out[7] = vp.m33() - vp.m30();
    normalizePlane(out, 4);
    out[8] = vp.m03() + vp.m01();
    out[9] = vp.m13() + vp.m11();
    out[10] = vp.m23() + vp.m21();
    out[11] = vp.m33() + vp.m31();
    normalizePlane(out, 8);
    out[12] = vp.m03() - vp.m01();
    out[13] = vp.m13() - vp.m11();
    out[14] = vp.m23() - vp.m21();
    out[15] = vp.m33() - vp.m31();
    normalizePlane(out, 12);
    out[16] = vp.m03() + vp.m02();
    out[17] = vp.m13() + vp.m12();
    out[18] = vp.m23() + vp.m22();
    out[19] = vp.m33() + vp.m32();
    normalizePlane(out, 16);
    out[20] = vp.m03() - vp.m02();
    out[21] = vp.m13() - vp.m12();
    out[22] = vp.m23() - vp.m22();
    out[23] = vp.m33() - vp.m32();
    normalizePlane(out, 20);
  }
  private static void normalizePlane(float[] planes, int offset) {
    float a = planes[offset], b = planes[offset + 1], c = planes[offset + 2];
    float len = (float) Math.sqrt(a * a + b * b + c * c);
    if (len > 0.0f) {
      float invLen = 1.0f / len;
      planes[offset] *= invLen;
      planes[offset + 1] *= invLen;
      planes[offset + 2] *= invLen;
      planes[offset + 3] *= invLen;
    }
  }
  public void endFrame() {
    MetalRenderer renderer = MetalRenderClient.getRenderer();
    if (renderer == null || !renderer.isAvailable())
      return;
    long frameCtx = renderer.getCurrentFrameContext();
    if (frameCtx != 0) {
      boolean terrainActive = renderer.getBackend() != null &&
          renderer.getBackend().getDefaultPipelineHandle() != 0;
      entityRenderer.renderCapturedEntities(frameCtx);
      particleRenderer.renderCapturedParticles(frameCtx);
      renderBlockOutline(frameCtx);
    }
    renderer.endFrame();
    frameCount++;
  }
  private void renderBlockOutline(long frameCtx) {
    try {
      MinecraftClient mc = MinecraftClient.getInstance();
      if (mc == null || mc.crosshairTarget == null)
        return;
      if (mc.crosshairTarget.getType() != HitResult.Type.BLOCK)
        return;
      BlockHitResult hit = (BlockHitResult) mc.crosshairTarget;
      BlockPos pos = hit.getBlockPos();
      Camera cam = mc.gameRenderer.getCamera();
      float bx = (float) (pos.getX() - cam.getCameraPos().x);
      float by = (float) (pos.getY() - cam.getCameraPos().y);
      float bz = (float) (pos.getZ() - cam.getCameraPos().z);
      float e = 0.002f;
      float x0 = bx - e, y0 = by - e, z0 = bz - e;
      float x1 = bx + 1 + e, y1 = by + 1 + e, z1 = bz + 1 + e;
      float t = 0.015f; 
      float[] verts = new float[72 * 3];
      int vi = 0;
      vi = addThickEdge(verts, vi, x0, y0, z0, x1, y0, z0, t, 1); 
      vi = addThickEdge(verts, vi, x1, y0, z0, x1, y0, z1, t, 1); 
      vi = addThickEdge(verts, vi, x1, y0, z1, x0, y0, z1, t, 1); 
      vi = addThickEdge(verts, vi, x0, y0, z1, x0, y0, z0, t, 1); 
      vi = addThickEdge(verts, vi, x0, y1, z0, x1, y1, z0, t, 1);
      vi = addThickEdge(verts, vi, x1, y1, z0, x1, y1, z1, t, 1);
      vi = addThickEdge(verts, vi, x1, y1, z1, x0, y1, z1, t, 1);
      vi = addThickEdge(verts, vi, x0, y1, z1, x0, y1, z0, t, 1);
      vi = addThickEdge(verts, vi, x0, y0, z0, x0, y1, z0, t, 0); 
      vi = addThickEdge(verts, vi, x1, y0, z0, x1, y1, z0, t, 2); 
      vi = addThickEdge(verts, vi, x1, y0, z1, x1, y1, z1, t, 0); 
      vi = addThickEdge(verts, vi, x0, y0, z1, x0, y1, z1, t, 2); 
      int vertexCount = vi / 3;
      ByteBuffer buf = ByteBuffer.allocateDirect(vi * 4)
          .order(ByteOrder.nativeOrder());
      for (int i = 0; i < vi; i++)
        buf.putFloat(verts[i]);
      buf.flip();
      byte[] data = new byte[buf.remaining()];
      buf.get(data);
      MetalRenderer renderer = MetalRenderClient.getRenderer();
      if (renderer == null)
        return;
      long device = renderer.getBackend().getDeviceHandle();
      if (outlineBufferHandle == 0 || data.length > outlineBufferSize) {
        if (outlineBufferHandle != 0) {
          NativeBridge.nDestroyBuffer(outlineBufferHandle);
        }
        outlineBufferHandle = NativeBridge.nCreateBuffer(
            device, data.length, NativeMemory.STORAGE_MODE_SHARED);
        outlineBufferSize = data.length;
      }
      NativeBridge.nUploadBufferData(outlineBufferHandle, data, 0, data.length);
      NativeBridge.nSetDebugColor(frameCtx, 0.0f, 0.0f, 0.0f, 0.5f);
      NativeBridge.nDrawTriangleBuffer(frameCtx, outlineBufferHandle, vertexCount);
    } catch (Exception e) {
      MetalLogger.error("[BlockOutline] Exception: %s", e.getMessage());
    }
  }
  private int outlineBufferSize = 0;
  private static int addThickEdge(float[] v, int vi,
      float ax, float ay, float az, float bx, float by, float bz,
      float t, int expandAxis) {
    float dx = 0, dy = 0, dz = 0;
    if (expandAxis == 0) dx = t;
    else if (expandAxis == 1) dy = t;
    else dz = t;
    float p0x = ax - dx, p0y = ay - dy, p0z = az - dz;
    float p1x = ax + dx, p1y = ay + dy, p1z = az + dz;
    float p2x = bx + dx, p2y = by + dy, p2z = bz + dz;
    float p3x = bx - dx, p3y = by - dy, p3z = bz - dz;
    v[vi++] = p0x; v[vi++] = p0y; v[vi++] = p0z;
    v[vi++] = p1x; v[vi++] = p1y; v[vi++] = p1z;
    v[vi++] = p2x; v[vi++] = p2y; v[vi++] = p2z;
    v[vi++] = p0x; v[vi++] = p0y; v[vi++] = p0z;
    v[vi++] = p2x; v[vi++] = p2y; v[vi++] = p2z;
    v[vi++] = p3x; v[vi++] = p3y; v[vi++] = p3z;
    return vi;
  }
  private final java.util.LinkedHashSet<Long> pendingBuildSet = new java.util.LinkedHashSet<>();
  private final java.util.ArrayList<Long> sortedBuildList = new java.util.ArrayList<>();
  private boolean sortedListDirty = true;
  private float cachedForwardX = 0, cachedForwardZ = 1;
  private int lastScanPlayerCX = Integer.MIN_VALUE, lastScanPlayerCZ = Integer.MIN_VALUE;
  private int lastScanRenderDist = -1;
  private static long packChunkKey(int cx, int cy, int cz) {
    return ((long) (cx & 0x3FFFFF) << 42) | ((long) (cy & 0xFFFFF) << 22) | (cz & 0x3FFFFF);
  }
  private void buildPendingChunkMeshes(MinecraftClient client) {
    scanForPendingChunks(client);
    if (client.player != null && chunkMesher.getMeshCount() < maxMeshes) {
      int playerChunkX = client.player.getChunkPos().x;
      int playerChunkZ = client.player.getChunkPos().z;
      float yaw = client.player.getYaw();
      cachedForwardX = (float) -Math.sin(Math.toRadians(yaw));
      cachedForwardZ = (float) Math.cos(Math.toRadians(yaw));
      buildFromPendingSet(playerChunkX, playerChunkZ, 0);
    }
  }
  private int scanFrameCounter = 0;
  private int scanFrontierRing = 0;
  private void scanForPendingChunks(MinecraftClient client) {
    ClientWorld world = client.world;
    if (world == null)
      return;
    if (client.player == null)
      return;
    int renderDist = client.options.getViewDistance().getValue();
    int playerChunkX = client.player.getChunkPos().x;
    int playerChunkZ = client.player.getChunkPos().z;
    boolean playerMovedChunk = (playerChunkX != lastScanPlayerCX ||
        playerChunkZ != lastScanPlayerCZ);
    boolean renderDistChanged = (renderDist != lastScanRenderDist);
    if (playerMovedChunk || renderDistChanged) {
      lastScanPlayerCX = playerChunkX;
      lastScanPlayerCZ = playerChunkZ;
      lastScanRenderDist = renderDist;
      sortedListDirty = true;
      if (renderDistChanged) {
        pendingBuildSet.clear();
        scanRingsInRange(world, playerChunkX, playerChunkZ, 0, renderDist);
        scanFrontierRing = 0;
        scanFrameCounter = 0;
      } else {
        int closeRange = Math.min(8, renderDist);
        scanRingsInRange(world, playerChunkX, playerChunkZ, 0, closeRange);
        scanFrontierRing = 0; 
      }
    }
    scanFrameCounter++;
    if (scanFrameCounter >= 60) {
      scanRingsInRange(world, playerChunkX, playerChunkZ, 0, renderDist);
      scanFrameCounter = 0;
      scanFrontierRing = 0;
    } else {
      int closeRange = Math.min(8, renderDist);
      if (!playerMovedChunk) {
        scanRingsInRange(world, playerChunkX, playerChunkZ, 0, closeRange);
      }
      int frontierStart = Math.max(closeRange + 1, scanFrontierRing);
      int frontierEnd = Math.min(frontierStart + 8, renderDist);
      if (frontierStart <= renderDist) {
        scanRingsInRange(world, playerChunkX, playerChunkZ, frontierStart, frontierEnd);
        scanFrontierRing = frontierEnd + 1;
        if (scanFrontierRing > renderDist) {
          scanFrontierRing = closeRange + 1;
        }
      }
    }
    logServerChunkAvailability(world, playerChunkX, playerChunkZ, renderDist);
  }
  private void scanRingsInRange(ClientWorld world, int playerChunkX, int playerChunkZ,
      int startRing, int endRing) {
    for (int ring = startRing; ring <= endRing; ring++) {
      for (int dx = -ring; dx <= ring; dx++) {
        for (int dz = -ring; dz <= ring; dz++) {
          if (ring > 0 && Math.abs(dx) < ring && Math.abs(dz) < ring)
            continue;
          int cx = playerChunkX + dx;
          int cz = playerChunkZ + dz;
          WorldChunk chunk = world.getChunkManager().getWorldChunk(cx, cz);
          if (chunk == null)
            continue;
          ChunkSection[] sections = chunk.getSectionArray();
          for (int sy = 0; sy < sections.length; sy++) {
            ChunkSection section = sections[sy];
            if (section == null || section.isEmpty())
              continue;
            int worldY = chunk.sectionIndexToCoord(sy);
            if (!chunkMesher.hasMesh(cx, worldY, cz)) {
              if (pendingBuildSet.add(packChunkKey(cx, worldY, cz))) {
                sortedListDirty = true;
              }
            }
          }
        }
      }
    }
  }
  private long lastChunkDiagMs = 0;
  private void logServerChunkAvailability(ClientWorld world, int playerChunkX, int playerChunkZ, int renderDist) {
    long now = System.currentTimeMillis();
    if (now - lastChunkDiagMs < 5000)
      return;
    lastChunkDiagMs = now;
    int available = 0, total = 0;
    int maxRingAvail = 0;
    for (int ring = 0; ring <= renderDist; ring++) {
      int ringAvail = 0;
      for (int dx = -ring; dx <= ring; dx++) {
        for (int dz = -ring; dz <= ring; dz++) {
          if (ring > 0 && Math.abs(dx) < ring && Math.abs(dz) < ring)
            continue;
          total++;
          if (world.getChunkManager().getWorldChunk(playerChunkX + dx, playerChunkZ + dz) != null) {
            available++;
            ringAvail++;
          }
        }
      }
      if (ringAvail > 0)
        maxRingAvail = ring;
    }
    MetalLogger.info("CHUNK_AVAIL: server=%d/%d (max_ring=%d) meshes=%d pending=%d",
        available, total, maxRingAvail, chunkMesher.getMeshCount(), pendingBuildSet.size());
  }
  public int buildMeshesDuringWait(long metalHandle) {
    MinecraftClient client = MinecraftClient.getInstance();
    if (client == null || client.player == null || client.world == null)
      return 0;
    if (chunkMesher.getMeshCount() >= maxMeshes)
      return 0;
    int playerChunkX = client.player.getChunkPos().x;
    int playerChunkZ = client.player.getChunkPos().z;
    int totalBuilt = 0;
    long timeout = System.nanoTime() + 12_000_000; 
    while (System.nanoTime() < timeout) {
      if (NativeBridge.nIsFrameReady(metalHandle)) {
        break;
      }
      int built = buildFromPendingSet(playerChunkX, playerChunkZ, 2_000_000L);
      if (built == 0)
        break; 
      totalBuilt += built;
    }
    return totalBuilt;
  }
  private int buildFromPendingSet(int playerChunkX, int playerChunkZ, long budgetNanos) {
    if (pendingBuildSet.isEmpty())
      return 0;
    if (sortedListDirty) {
      sortedBuildList.clear();
      sortedBuildList.addAll(pendingBuildSet);
      final int pcx = playerChunkX;
      final int pcz = playerChunkZ;
      final float fwdX = cachedForwardX;
      final float fwdZ = cachedForwardZ;
      sortedBuildList.sort((a, b) -> {
        int ax = (int) ((a >> 42) & 0x3FFFFF);
        if ((ax & 0x200000) != 0)
          ax |= ~0x3FFFFF;
        int az = (int) (a & 0x3FFFFF);
        if ((az & 0x200000) != 0)
          az |= ~0x3FFFFF;
        int bx = (int) ((b >> 42) & 0x3FFFFF);
        if ((bx & 0x200000) != 0)
          bx |= ~0x3FFFFF;
        int bz = (int) (b & 0x3FFFFF);
        if ((bz & 0x200000) != 0)
          bz |= ~0x3FFFFF;
        float dotA = (ax - pcx) * fwdX + (az - pcz) * fwdZ;
        float dotB = (bx - pcx) * fwdX + (bz - pcz) * fwdZ;
        boolean frontA = dotA >= 0;
        boolean frontB = dotB >= 0;
        if (frontA != frontB)
          return frontA ? -1 : 1;
        int distA = Math.abs(ax - pcx) + Math.abs(az - pcz);
        int distB = Math.abs(bx - pcx) + Math.abs(bz - pcz);
        return Integer.compare(distA, distB);
      });
      sortedListDirty = false;
    }
    long deadline = budgetNanos > 0 ? System.nanoTime() + budgetNanos : Long.MAX_VALUE;
    int maxSubmit = 500;
    int built = 0;
    int i = 0;
    while (i < sortedBuildList.size() && built < maxSubmit && chunkMesher.getMeshCount() < maxMeshes) {
      if (budgetNanos > 0 && built > 0 && System.nanoTime() >= deadline)
        break;
      long key = sortedBuildList.get(i);
      int cx = (int) ((key >> 42) & 0x3FFFFF);
      if ((cx & 0x200000) != 0)
        cx |= ~0x3FFFFF;
      int cy = (int) ((key >> 22) & 0xFFFFF);
      if ((cy & 0x80000) != 0)
        cy |= ~0xFFFFF;
      int cz = (int) (key & 0x3FFFFF);
      if ((cz & 0x200000) != 0)
        cz |= ~0x3FFFFF;
      if (chunkMesher.hasMesh(cx, cy, cz)) {
        sortedBuildList.remove(i);
        pendingBuildSet.remove(key);
        continue;
      }
      int chunkDist = Math.max(Math.abs(cx - playerChunkX), Math.abs(cz - playerChunkZ));
      int lodLevel = com.pebbles_boon.metalrender.config.MetalRenderConfig.getLodLevel(chunkDist);
      chunkMesher.buildMeshFromWorld(cx, cy, cz, lodLevel);
      sortedBuildList.remove(i);
      pendingBuildSet.remove(key);
      built++;
    }
    return built;
  }
  private int lodScanOffset = 0;
  private void rebuildLodMeshes(MinecraftClient client) {
    if (client.player == null || client.world == null)
      return;
    int playerChunkX = client.player.getChunkPos().x;
    int playerChunkZ = client.player.getChunkPos().z;
    int rebuilt = 0;
    long deadline = System.nanoTime() + 2_000_000L; 
    int maxScansPerPass = 2048; 
    int scanned = 0;
    var allMeshes = chunkMesher.getAllMeshes();
    if (allMeshes.isEmpty())
      return;
    var iter = allMeshes.iterator();
    int skip = lodScanOffset;
    while (skip > 0 && iter.hasNext()) {
      iter.next();
      skip--;
    }
    while (iter.hasNext() && scanned < maxScansPerPass) {
      if (rebuilt > 0 && System.nanoTime() >= deadline)
        break;
      CustomChunkMesher.ChunkMeshData mesh = iter.next();
      scanned++;
      int dx = mesh.chunkX - playerChunkX;
      int dz = mesh.chunkZ - playerChunkZ;
      int chunkDist = Math.max(Math.abs(dx), Math.abs(dz));
      int desiredLod = com.pebbles_boon.metalrender.config.MetalRenderConfig
          .getLodLevel(chunkDist);
      if (chunkMesher.needsLodRebuild(mesh.chunkX, mesh.chunkY, mesh.chunkZ,
          desiredLod)) {
        chunkMesher.buildMeshFromWorld(mesh.chunkX, mesh.chunkY, mesh.chunkZ,
            desiredLod);
        rebuilt++;
      }
    }
    lodScanOffset += scanned;
    if (!iter.hasNext() || lodScanOffset >= allMeshes.size()) {
      lodScanOffset = 0;
    }
    if (rebuilt > 0 && frameCount % 60 == 0) {
      MetalLogger.info("[LOD_REBUILD] Rebuilt %d meshes (scanned %d, offset %d)",
          rebuilt, scanned, lodScanOffset);
    }
  }
  public int getGLTextureForCompositing() {
    MetalRenderer renderer = MetalRenderClient.getRenderer();
    if (renderer == null)
      return 0;
    return renderer.getGLTextureId();
  }
  public FrustumCuller getFrustumCuller() {
    return frustumCuller;
  }
  public MetalEntityRenderer getEntityRenderer() {
    return entityRenderer;
  }
  public MetalParticleRenderer getParticleRenderer() {
    return particleRenderer;
  }
  public CustomChunkMesher getChunkMesher() {
    return chunkMesher;
  }
  public void setLastDrawnChunkCount(int c) {
    this.lastDrawnChunkCount = c;
  }
  public void addDrawnChunkCount(int c) {
    this.lastDrawnChunkCount += c;
  }
  public int getLastDrawnChunkCount() {
    return lastDrawnChunkCount;
  }
  public boolean areTexturesReady() {
    return texturesReady;
  }
  public MetalTextureManager getTextureManager() {
    return textureManager;
  }
  public boolean isWorldLoaded() {
    return worldLoaded;
  }
  public int getFrameCount() {
    return frameCount;
  }
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
  public static String getBlitTimingMode() {
    return "flip_head";
  }
  public void forceBlitNow() {
    MetalRenderer renderer = MetalRenderClient.getRenderer();
    if (renderer == null || !renderer.isAvailable())
      return;
    long handle = renderer.getHandle();
    if (handle == 0)
      return;
    ioSurfaceBlitter.blit(handle);
  }
  public void forceBlitDepthNow(int width, int height) {
    MetalRenderer renderer = MetalRenderClient.getRenderer();
    if (renderer == null || !renderer.isAvailable())
      return;
    long handle = renderer.getHandle();
    if (handle == 0)
      return;
    ioSurfaceBlitter.blitDepth(handle, width, height);
  }
  public boolean uploadDepthDirect(int mcDepthTexId, int width, int height) {
    MetalRenderer renderer = MetalRenderClient.getRenderer();
    if (renderer == null || !renderer.isAvailable())
      return false;
    long handle = renderer.getHandle();
    if (handle == 0)
      return false;
    return ioSurfaceBlitter.uploadDepthDirect(handle, mcDepthTexId, width,
        height);
  }
  public boolean blitDepthViaFBO(int mcDepthTexId, int mcFboId, int width,
      int height) {
    MetalRenderer renderer = MetalRenderClient.getRenderer();
    if (renderer == null || !renderer.isAvailable())
      return false;
    long handle = renderer.getHandle();
    if (handle == 0)
      return false;
    return ioSurfaceBlitter.blitDepthViaFBO(handle, mcDepthTexId, mcFboId,
        width, height);
  }
  public boolean isReady() {
    return worldLoaded && renderingActive;
  }
  public void renderFrame(Object viewport, Object matrices, double x, double y,
      double z) {
  }
  public void onChunkLoaded(int chunkX, int chunkZ, net.minecraft.world.chunk.WorldChunk chunk) {
    if (!worldLoaded || !renderingActive)
      return;
    net.minecraft.world.chunk.ChunkSection[] sections = chunk.getSectionArray();
    for (int sy = 0; sy < sections.length; sy++) {
      net.minecraft.world.chunk.ChunkSection section = sections[sy];
      if (section == null || section.isEmpty())
        continue;
      int worldY = chunk.sectionIndexToCoord(sy);
      if (!chunkMesher.hasMesh(chunkX, worldY, chunkZ)) {
        if (pendingBuildSet.add(packChunkKey(chunkX, worldY, chunkZ))) {
          sortedListDirty = true;
        }
      }
      chunkMesher.markDirty(chunkX - 1, worldY, chunkZ);
      chunkMesher.markDirty(chunkX + 1, worldY, chunkZ);
      chunkMesher.markDirty(chunkX, worldY - 1, chunkZ);
      chunkMesher.markDirty(chunkX, worldY + 1, chunkZ);
      chunkMesher.markDirty(chunkX, worldY, chunkZ - 1);
      chunkMesher.markDirty(chunkX, worldY, chunkZ + 1);
    }
  }
  public void scheduleSectionRebuild(int blockX, int blockY, int blockZ) {
    if (!worldLoaded || !renderingActive) {
      return;
    }
    int cx = blockX >> 4;
    int cy = blockY >> 4;
    int cz = blockZ >> 4;
    chunkMesher.markDirty(cx, cy, cz);
    chunkMesher.buildMeshFromWorld(cx, cy, cz, 0, true);
    int localX = blockX & 15;
    int localY = blockY & 15;
    int localZ = blockZ & 15;
    if (localX == 0) {
      chunkMesher.markDirty(cx - 1, cy, cz);
      chunkMesher.buildMeshFromWorld(cx - 1, cy, cz, 0, true);
    }
    if (localX == 15) {
      chunkMesher.markDirty(cx + 1, cy, cz);
      chunkMesher.buildMeshFromWorld(cx + 1, cy, cz, 0, true);
    }
    if (localY == 0) {
      chunkMesher.markDirty(cx, cy - 1, cz);
      chunkMesher.buildMeshFromWorld(cx, cy - 1, cz, 0, true);
    }
    if (localY == 15) {
      chunkMesher.markDirty(cx, cy + 1, cz);
      chunkMesher.buildMeshFromWorld(cx, cy + 1, cz, 0, true);
    }
    if (localZ == 0) {
      chunkMesher.markDirty(cx, cy, cz - 1);
      chunkMesher.buildMeshFromWorld(cx, cy, cz - 1, 0, true);
    }
    if (localZ == 15) {
      chunkMesher.markDirty(cx, cy, cz + 1);
      chunkMesher.buildMeshFromWorld(cx, cy, cz + 1, 0, true);
    }
  }
  public boolean isGPUDrivenEnabled() {
    return gpuDrivenEnabled;
  }
  public MeshShaderBackend getMeshShaderBackend() {
    return meshShaderBackend;
  }
  public int getLastGPUVisibleCount() {
    return lastGPUVisibleCount;
  }
  public int[] getGPUCullStats() {
    NativeBridge.nGetGPUCullStats(gpuCullStats);
    return gpuCullStats;
  }
  public int getThermalState() {
    return NativeBridge.nGetThermalState();
  }
  public int getThermalLODReduction() {
    return NativeBridge.nGetThermalLODReduction();
  }
}
