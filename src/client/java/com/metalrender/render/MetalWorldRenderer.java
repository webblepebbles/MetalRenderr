package com.metalrender.render;

import com.metalrender.MetalRenderClient;
import com.metalrender.config.MetalRenderConfig;
import com.metalrender.lod.DistantChunkLoader;
import com.metalrender.lod.ExtendedLodRenderer;
import com.metalrender.lod.LodChunkIngestor;
import com.metalrender.lod.LodChunkStorage;
import com.metalrender.lod.LodRingTracker;
import com.metalrender.nativebridge.NativeBridge;
import com.metalrender.performance.PerformanceController;
import com.metalrender.performance.RenderDistanceManager;
import com.metalrender.performance.RenderOptimizer;
import com.metalrender.render.atlas.CapturedAtlas;
import com.metalrender.render.atlas.CapturedAtlasRepository;
import com.metalrender.render.MetalSurfaceManager;
import com.metalrender.render.GpuLodOrchestrator;
import com.metalrender.render.MetalFXSpatialUpscaler;
import com.metalrender.render.TripleBufferPacer;
import com.metalrender.render.ArgumentBufferManager;
import com.metalrender.sodium.backend.MeshShaderBackend;
import com.metalrender.sodium.hooks.ChunkOutputBridge;
import com.metalrender.temporal.TemporalAA;
import com.metalrender.temporal.TemporalUpscaler;
import com.metalrender.util.MetalLogger;
import com.metalrender.util.PersistentBufferArena;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

public class MetalWorldRenderer {
  private static final Identifier BLOCKS_ATLAS_ID = Identifier.of("minecraft",
      "textures/atlas/blocks.png");

  private long handle;
  private volatile boolean ready;
  private volatile boolean renderedThisFrame = false;
  private volatile boolean atlasNeedsReupload = false;
  private volatile boolean atlasUploaded = false;
  private int frameCount = 0;
  private final RenderOptimizer renderOptimizer = RenderOptimizer.getInstance();
  private final PersistentBufferArena persistentArena = new PersistentBufferArena();
  private final float[] frameViewProjection = new float[16];
  private final TemporalAA temporalAA = new TemporalAA();
  private final TemporalUpscaler temporalUpscaler = new TemporalUpscaler();
  private final IOSurfaceBlitter ioSurfaceBlitter = new IOSurfaceBlitter();
  private GpuLodOrchestrator gpuLodOrchestrator;
  private MetalFXSpatialUpscaler metalFXSpatial;
  private TripleBufferPacer tripleBufferPacer;
  private ArgumentBufferManager argumentBufferManager;
  private MeshShaderBackend cachedMeshBackend;
  private PipelineCache pipelineCache;
  private LodRingTracker lodRingTracker;
  private LodChunkStorage lodChunkStorage;
  private ExtendedLodRenderer extendedLodRenderer;
  private boolean extendedLodInitialized = false;
  private long lastFrameNanoTime = 0;
  private int lastWidth = 16;
  private int lastHeight = 16;
  private float lastScale = 1.0F;

  public MetalWorldRenderer() {
    MetalLogger.info(
        "MetalWorldRenderer created (initializing native backend)");

    try {
      if (NativeBridge.isLibLoaded() && NativeBridge.nIsAvailable()) {
        this.handle = NativeBridge.nInit(16, 16, 1.0F);
        this.ready = this.handle != 0L;
        MetalLogger.info("Native backend {} (device='{}')",
            this.ready ? "ready" : "failed",
            NativeBridge.nGetDeviceName(this.handle));
        if (this.ready) {
          String shadersPath = findShadersPath();
          if (shadersPath != null) {
            NativeBridge.nSetShadersPath(this.handle, shadersPath);
            MetalLogger.info("Shaders path set to: {}", shadersPath);
          } else {
            MetalLogger.warn("Could not find shaders.metallib");
          }

          if (!this.persistentArena.initialize(this.handle)) {
            MetalLogger.warn("Persistent buffer arena failed to initialize; "
                + "falling back to transient uploads");
          }
          this.pipelineCache = PipelineCache.create(this.handle);
          if (this.pipelineCache != null) {
            this.pipelineCache.prewarm();
          }
          NativeBridge.nPrewarmPipelines(this.handle);
          this.gpuLodOrchestrator = new GpuLodOrchestrator(this.handle);
          if (MetalRenderConfig.multiICBEnabled()) {
            this.gpuLodOrchestrator.initialize();
          }
          this.tripleBufferPacer = new TripleBufferPacer(this.handle);
          this.tripleBufferPacer.syncFromConfig();
          this.argumentBufferManager = new ArgumentBufferManager(this.handle);
          if (MetalRenderConfig.argumentBuffersEnabled()) {
            this.argumentBufferManager.initialize();
          }
          this.metalFXSpatial = new MetalFXSpatialUpscaler(this.handle);
          if (MetalRenderConfig.extendedLodEnabled()) {
            this.initExtendedLod();
          }
        }
      } else {
        this.ready = false;
        MetalLogger.warn("Native library not loaded or unavailable");
      }
    } catch (Throwable var2) {
      this.ready = false;
      MetalLogger.error("Failed to initialize native backend: " + var2);
      var2.printStackTrace();
    }
  }

  private String findShadersPath() {
    String[] possiblePaths = {
        "src/main/resources/shaders.metallib",
        "shaders.metallib",
        System.getProperty("user.dir") + "/shaders.metallib",
    };
    try {
      java.io.InputStream stream = MetalWorldRenderer.class.getClassLoader()
          .getResourceAsStream("shaders.metallib");
      if (stream != null) {
        java.io.File tempFile = java.io.File.createTempFile("metalrender_shaders", ".metallib");
        tempFile.deleteOnExit();
        try (java.io.OutputStream out = new java.io.FileOutputStream(tempFile)) {
          byte[] buffer = new byte[8192];
          int bytesRead;
          while ((bytesRead = stream.read(buffer)) != -1) {
            out.write(buffer, 0, bytesRead);
          }
        }
        stream.close();
        MetalLogger.info("Extracted shaders.metallib to: {}", tempFile.getAbsolutePath());
        return tempFile.getAbsolutePath();
      }
    } catch (Exception e) {
      MetalLogger.warn("Failed to extract shaders.metallib from classpath: {}", e.getMessage());
    }
    for (String path : possiblePaths) {
      java.io.File file = new java.io.File(path);
      if (file.exists() && file.isFile()) {
        return file.getAbsolutePath();
      }
    }

    return null;
  }

  public boolean isReady() {
    return this.ready;
  }

  public void renderFrame(Object viewport, Object matrices, double x, double y,
      double z) {
    if (this.ready && this.handle != 0L) {
      try {
        if ((this.atlasNeedsReupload || !this.atlasUploaded)) {
          this.tryUploadAtlas();
        }

        if (this.frameCount < 5) {
          MetalLogger.info("[MetalRender] renderFrame called (frame %d)", this.frameCount);
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        Camera camera = mc.gameRenderer.getCamera();
        int framebufferWidth = Math.max(1, mc.getWindow().getFramebufferWidth());
        int framebufferHeight = Math.max(1, mc.getWindow().getFramebufferHeight());
        this.temporalUpscaler.updateScale();
        if (framebufferWidth != this.lastWidth ||
            framebufferHeight != this.lastHeight) {
          NativeBridge.nResize(this.handle, framebufferWidth, framebufferHeight,
              this.lastScale);
          this.lastWidth = framebufferWidth;
          this.lastHeight = framebufferHeight;
        }
        MetalSurfaceManager.ensureSurface(this.handle);

        Matrix4f projection = new Matrix4f().setPerspective(
            this.getFovRadians(mc),
            (float) framebufferWidth / (float) framebufferHeight, 0.05F,
            MetalRenderConfig.fogEndDistance());
        Matrix4f correction = new Matrix4f().translation(0, 0, 0.5f).scale(1, 1, 0.5f);
        correction.mul(projection, projection);

        this.temporalAA.beginFrame(framebufferWidth, framebufferHeight);
        this.temporalAA.applyJitter(projection);
        Matrix4f view = this.buildViewMatrix(camera);
        Matrix4f viewProjMatrix = new Matrix4f();
        projection.mul(view, viewProjMatrix);
        this.renderOptimizer.updateFrame(this.handle, camera, viewProjMatrix,
            framebufferWidth, framebufferHeight);

        float scale = MetalRenderConfig.resolutionScale();
        if (Math.abs(scale - this.lastScale) > 0.001F) {
          NativeBridge.nResize(this.handle, this.lastWidth, this.lastHeight,
              scale);
          this.lastScale = scale;
        }

        viewProjMatrix.get(this.frameViewProjection);
        NativeBridge.nSetTemporalJitter(this.handle, this.temporalAA.jitterX(),
            this.temporalAA.jitterY(),
            this.temporalAA.blendFactor());
        if (this.pipelineCache != null) {
          this.pipelineCache.prewarm();
        }
        Vec3d cameraPos = camera.getCameraPos();
        float[] cameraPosArray = new float[] {
            (float) cameraPos.x,
            (float) cameraPos.y,
            (float) cameraPos.z
        };
        this.updateLightParams();

        NativeBridge.nBeginFrame(this.handle, this.frameViewProjection, cameraPosArray,
            0.0F, MetalRenderConfig.fogEndDistance());
        NativeBridge.nClearIndirectCommands(this.handle);

        MeshShaderBackend backend = this.meshBackend();
        int queued = 0;
        int meshCount = 0;
        if (backend != null) {
          meshCount = backend.getMeshCount();
          queued = backend.emitDraws(this.handle, this.persistentArena, this.renderOptimizer, camera);
        }
        if (this.frameCount < 3 || this.frameCount % 600 == 0) {
          MetalLogger.info(
              "[MetalRender] Frame %d: queued %d draw commands from %d meshes, camera at (%.1f, %.1f, %.1f)",
              this.frameCount, queued, meshCount, cameraPos.x, cameraPos.y, cameraPos.z);
        }

        RenderOptimizer.PerformanceStats stats = this.renderOptimizer.getFrameStats();
        int drawn = Math.max(0, stats.totalChunks - stats.frustumCulled -
            stats.occlusionCulled);
        PerformanceController.accumulateChunkStats(stats.totalChunks, drawn,
            stats.frustumCulled,
            stats.occlusionCulled);
        NativeBridge.nExecuteIndirect(this.handle, queued);
        NativeBridge.nDrawTerrain(this.handle, 0);
        this.renderOptimizer.finalizeFrame();
        if (this.frameCount < 10) {
          MetalLogger.info("[MetalRender] Frame %d: Terrain drawn, queued=%d meshes=%d",
              this.frameCount, queued, meshCount);
        }
        RenderDistanceManager.getInstance().updateRenderDistance();

        this.renderedThisFrame = true;
        this.frameCount++;
      } catch (Throwable var15) {
        MetalLogger.error("renderFrame failed", var15);
      }
    }
  }

  public void renderFrameWithMatrices(Matrix4fc projection, Matrix4fc modelView, double x, double y, double z) {
    if (this.ready && this.handle != 0L) {
      try {
        if ((this.atlasNeedsReupload || !this.atlasUploaded)) {
          this.tryUploadAtlas();
        }

        if (this.frameCount < 5) {
          MetalLogger.info("[MetalRender] renderFrameWithMatrices called (frame %d) with Sodium matrices!",
              this.frameCount);
        }

        MinecraftClient mc = MinecraftClient.getInstance();
        Camera camera = mc.gameRenderer.getCamera();
        int framebufferWidth = Math.max(1, mc.getWindow().getFramebufferWidth());
        int framebufferHeight = Math.max(1, mc.getWindow().getFramebufferHeight());

        this.temporalUpscaler.updateScale();
        if (framebufferWidth != this.lastWidth || framebufferHeight != this.lastHeight) {
          NativeBridge.nResize(this.handle, framebufferWidth, framebufferHeight, this.lastScale);
          this.lastWidth = framebufferWidth;
          this.lastHeight = framebufferHeight;
        }

        float scale = MetalRenderConfig.resolutionScale();
        if (Math.abs(scale - this.lastScale) > 0.001F) {
          NativeBridge.nResize(this.handle, this.lastWidth, this.lastHeight, scale);
          this.lastScale = scale;
        }
        Matrix4f viewProjMatrix = new Matrix4f();
        projection.mul(modelView, viewProjMatrix);
        boolean matricesValid = true;
        if (!Float.isFinite(projection.m00()) || !Float.isFinite(projection.m11())) {
          if (this.frameCount < 10) {
            MetalLogger.error("[TEST53] SKIPPING frame %d - projection has Infinity (m00=%.2f, m11=%.2f)",
                this.frameCount, projection.m00(), projection.m11());
          }
          matricesValid = false;
        }
        if (!Float.isFinite(viewProjMatrix.m00()) || !Float.isFinite(viewProjMatrix.m11()) ||
            Float.isNaN(viewProjMatrix.m00()) || Float.isNaN(viewProjMatrix.m11())) {
          if (this.frameCount < 10) {
            MetalLogger.error("[TEST53] SKIPPING frame %d - viewProj has invalid values (m00=%.2f, m11=%.2f)",
                this.frameCount, viewProjMatrix.m00(), viewProjMatrix.m11());
          }
          matricesValid = false;
        }
        if (this.frameCount < 10 && matricesValid) {
          MetalLogger.info("[TEST53] VALID frame %d:", this.frameCount);
          MetalLogger.info("  Projection diag: %.4f, %.4f, %.4f, %.4f",
              projection.m00(), projection.m11(), projection.m22(), projection.m33());
          MetalLogger.info("  ModelView m30/m31/m32: %.4f, %.4f, %.4f (translation)",
              modelView.m30(), modelView.m31(), modelView.m32());
          MetalLogger.info("  ViewProj Row3: %.4f, %.4f, %.4f, %.4f",
              viewProjMatrix.m03(), viewProjMatrix.m13(), viewProjMatrix.m23(), viewProjMatrix.m33());
          MetalLogger.info("  Camera pos: %.2f, %.2f, %.2f", x, y, z);
        }

        if (!matricesValid) {
          return;
        }

        this.renderOptimizer.updateFrame(this.handle, camera, viewProjMatrix,
            framebufferWidth, framebufferHeight);

        viewProjMatrix.get(this.frameViewProjection);

        this.temporalAA.beginFrame(framebufferWidth, framebufferHeight);
        NativeBridge.nSetTemporalJitter(this.handle, this.temporalAA.jitterX(),
            this.temporalAA.jitterY(), this.temporalAA.blendFactor());

        if (this.pipelineCache != null) {
          this.pipelineCache.prewarm();
        }
        float[] cameraPosArray = new float[] {
            (float) x,
            (float) y,
            (float) z
        };

        if (this.frameCount < 5) {
          MetalLogger.info("[MetalRender] Camera pos from Sodium: (%.2f, %.2f, %.2f)", x, y, z);
        }
        this.updateLightParams();

        NativeBridge.nBeginFrame(this.handle, this.frameViewProjection, cameraPosArray,
            0.0F, MetalRenderConfig.fogEndDistance());
        NativeBridge.nClearIndirectCommands(this.handle);

        MeshShaderBackend backend = this.meshBackend();
        int queued = 0;
        int meshCount = 0;
        if (backend != null) {
          meshCount = backend.getMeshCount();
          queued = backend.emitDraws(this.handle, this.persistentArena, this.renderOptimizer, camera);
        }

        if (this.frameCount < 3 || this.frameCount % 600 == 0) {
          MetalLogger.info("[MetalRender] Frame %d: queued %d draw commands from %d meshes",
              this.frameCount, queued, meshCount);
        }

        RenderOptimizer.PerformanceStats stats = this.renderOptimizer.getFrameStats();
        int drawn = Math.max(0, stats.totalChunks - stats.frustumCulled - stats.occlusionCulled);
        PerformanceController.accumulateChunkStats(stats.totalChunks, drawn,
            stats.frustumCulled, stats.occlusionCulled);

        NativeBridge.nExecuteIndirect(this.handle, queued);
        NativeBridge.nDrawTerrain(this.handle, 0);
        this.renderOptimizer.finalizeFrame();
        RenderDistanceManager.getInstance().updateRenderDistance();
        if (MetalRenderConfig.extendedLodEnabled() && this.lodRingTracker != null) {
          float cameraPitch = 0.0f;
          int altAboveGround = 64;
          if (camera != null) {
            cameraPitch = camera.getPitch();
            try {
              MinecraftClient mcAlt = MinecraftClient.getInstance();
              if (mcAlt != null && mcAlt.player != null) {
                altAboveGround = Math.max(0, (int) (mcAlt.player.getY() -
                    mcAlt.world.getBottomY()));
              }
            } catch (Exception ignored) {
            }
          }
          this.updateExtendedLod((float) x, (float) y, (float) z,
              viewProjMatrix, cameraPitch, altAboveGround);
        }

        this.renderedThisFrame = true;
        this.frameCount++;
      } catch (Throwable var15) {
        MetalLogger.error("renderFrameWithMatrices failed: %s", var15.toString());
        var15.printStackTrace();
      }
    }
  }

  public void uploadBuildResult(Object result) {
    if (!this.ready || this.handle == 0L || result == null) {
      return;
    }

    if (!(result instanceof ChunkBuildOutput output)) {
      return;
    }

    MeshShaderBackend backend = this.meshBackend();
    if (backend != null) {
      backend.uploadBuildOutput(this.handle, this.persistentArena, output);
    }
  }

  private MeshShaderBackend meshBackend() {
    MeshShaderBackend backend = this.cachedMeshBackend;
    if (backend == null) {
      backend = MetalRenderClient.getMeshBackend();
      if (backend != null) {
        this.cachedMeshBackend = backend;
      }
    }
    return backend;
  }

  private void updateLightParams() {
    try {
      MinecraftClient mc = MinecraftClient.getInstance();
      if (mc == null || mc.world == null) {
        NativeBridge.nSetLightParams(this.handle, 1.0f, 0.1f, 0.0f);
        return;
      }
      ClientWorld world = mc.world;
      float dayBrightness = world.getEnvironmentAttributes()
          .getAttributeValue(EnvironmentAttributes.SKY_LIGHT_FACTOR_VISUAL);
      boolean hasNightVision = false;
      if (mc.player != null) {
        hasNightVision = mc.player.hasStatusEffect(StatusEffects.NIGHT_VISION);
      }
      if (hasNightVision) {
        dayBrightness = 1.0f;
      }
      float ambientLight = Math.max(0.15f, dayBrightness * 0.35f);
      if (hasNightVision) {
        ambientLight = 0.7f;
      }
      float skyAngle = world.getEnvironmentAttributes().getAttributeValue(EnvironmentAttributes.SUN_ANGLE_VISUAL)
          / 360.0f;

      NativeBridge.nSetLightParams(this.handle, dayBrightness, ambientLight, skyAngle);
    } catch (Throwable e) {
      NativeBridge.nSetLightParams(this.handle, 1.0f, 0.1f, 0.0f);
    }
  }

  private Matrix4f buildViewMatrix(Camera camera) {
    Matrix4f view = new Matrix4f().identity();
    float pitch = (float) Math.toRadians(camera.getPitch());
    float yaw = (float) Math.toRadians(camera.getYaw() + 180.0F);
    view.rotateY(-yaw);
    view.rotateX(-pitch);
    net.minecraft.util.math.Vec3d pos = camera.getCameraPos();
    view.translate((float) -pos.x, (float) -pos.y, (float) -pos.z);
    return view;
  }

  private float getFovRadians(MinecraftClient client) {
    double fov = 70.0D;
    try {
      fov = client.options.getFov().getValue();
    } catch (Throwable ignored) {
    }
    return (float) Math.toRadians(fov);
  }

  public PersistentBufferArena getPersistentArena() {
    return this.persistentArena;
  }

  public void destroy() {
    this.cachedMeshBackend = null;
    if (this.pipelineCache != null) {
      this.pipelineCache.reset();
      this.pipelineCache = null;
    }
    if (this.gpuLodOrchestrator != null) {
      this.gpuLodOrchestrator.destroy();
      this.gpuLodOrchestrator = null;
    }
    if (this.metalFXSpatial != null) {
      this.metalFXSpatial.destroy();
      this.metalFXSpatial = null;
    }
    if (this.argumentBufferManager != null) {
      this.argumentBufferManager.destroy();
      this.argumentBufferManager = null;
    }
    this.shutdownExtendedLod();
    this.ioSurfaceBlitter.destroy();
    if (this.handle != 0L) {
      NativeBridge.nDestroy(this.handle);
      this.handle = 0L;
    }
    this.ready = false;
  }

  public boolean hasRenderedThisFrame() {
    return this.renderedThisFrame;
  }

  public void resetFrameState() {
    this.renderedThisFrame = false;
    this.blittedThisFrame = false;
  }

  private boolean blittedThisFrame = false;

  public void forceBlitNow() {
    if (this.ready && this.handle != 0L) {
      if (!this.hasRenderedThisFrame()) {
        if (this.frameCount < 10) {
          MetalLogger.warn("[MetalRender] forceBlitNow called but no terrain was rendered this frame!");
        }
        return;
      }

      NativeBridge.nWaitForRender(this.handle);

      int currentFbo = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_BINDING);
      int[] viewport = new int[4];
      org.lwjgl.opengl.GL11.glGetIntegerv(org.lwjgl.opengl.GL11.GL_VIEWPORT, viewport);

      if (this.frameCount < 10 || this.frameCount % 600 == 0) {
        MetalLogger.info("[MetalRender] forceBlitNow: currentFBO=%d, Viewport=[%d,%d,%d,%d]",
            currentFbo, viewport[0], viewport[1], viewport[2], viewport[3]);
      }

      boolean blitSuccess = this.ioSurfaceBlitter.blit(this.handle);
      if (this.frameCount < 10) {
        MetalLogger.info("[MetalRender] Blit result: %s", blitSuccess ? "SUCCESS" : "FAILED");
      }
    }
  }

  public void blitToScreen() {
    if (this.ready && this.handle != 0L && this.renderedThisFrame && !this.blittedThisFrame) {
      this.ioSurfaceBlitter.blit(this.handle);
      this.blittedThisFrame = true;
    }
  }

  private void tryUploadAtlas() {
    Optional<CapturedAtlas> atlasOpt = CapturedAtlasRepository.get(BLOCKS_ATLAS_ID);
    if (atlasOpt.isEmpty()) {
      return;
    }

    CapturedAtlas atlas = atlasOpt.get();
    ByteBuffer buffer = atlas.toDirectBuffer();

    boolean success = NativeBridge.nUploadAtlas(this.handle, buffer, atlas.width(), atlas.height());
    if (success) {
      this.atlasUploaded = true;
      this.atlasNeedsReupload = false;
      MetalLogger.info("[MetalRender] Atlas uploaded to Metal: {}x{}", atlas.width(), atlas.height());
    } else {
      MetalLogger.warn("[MetalRender] Failed to upload atlas to Metal");
    }
  }

  public void forceAtlasReupload() {
    this.atlasNeedsReupload = true;
  }

  public void flushChunkUploads() {
    ChunkOutputBridge.drain(this);
  }

  public void uploadBuildResults(List<ChunkBuildOutput> outputs) {
    if (outputs == null || outputs.isEmpty() || !this.ready || this.handle == 0L) {
      return;
    }
    MeshShaderBackend backend = this.meshBackend();
    if (backend != null) {
      for (ChunkBuildOutput output : outputs) {
        if (output != null) {
          backend.uploadBuildOutput(this.handle, this.persistentArena, output);
        }
      }
    }
  }

  public long getHandle() {
    return this.handle;
  }

  public boolean uploadAtlas() {
    this.tryUploadAtlas();
    return this.atlasUploaded;
  }

  public void setsodiumless(boolean enabled) {
  }

  private void initExtendedLod() {
    try {
      MinecraftClient mc = MinecraftClient.getInstance();
      java.nio.file.Path basePath;
      if (mc != null && mc.runDirectory != null) {
        basePath = mc.runDirectory.toPath().resolve("metalrender").resolve("lod_storage.dat");
      } else {
        basePath = java.nio.file.Path.of("metalrender").resolve("lod_storage.dat");
      }

      this.lodChunkStorage = new LodChunkStorage(basePath,
          MetalRenderConfig.extendedLodCacheSize());
      if (!this.lodChunkStorage.initialize()) {
        MetalLogger.warn("[ExtendedLOD] Storage initialization failed");
        this.lodChunkStorage = null;
        return;
      }

      this.lodRingTracker = new LodRingTracker();
      if (MetalRenderConfig.extendedLodIngestEnabled()) {
        LodChunkIngestor.getInstance().initialize(this.lodChunkStorage);
      }

      if (MetalRenderConfig.extremeRenderDistance() > 32) {
        DistantChunkLoader.getInstance().initialize(this.lodChunkStorage);
      }

      this.extendedLodRenderer = new ExtendedLodRenderer();
      if (!this.extendedLodRenderer.initialize(this.handle, this.persistentArena)) {
        MetalLogger.warn("[ExtendedLOD] Renderer init failed (arena may not be ready)");
      }

      this.extendedLodInitialized = true;
      MetalLogger.info("[ExtendedLOD] System initialized (distance={} chunks, cache={})",
          MetalRenderConfig.extremeRenderDistance(),
          MetalRenderConfig.extendedLodCacheSize());
    } catch (Exception e) {
      MetalLogger.error("[ExtendedLOD] Init failed: {}", e.getMessage());
      this.extendedLodInitialized = false;
    }
  }

  private void updateExtendedLod(float camX, float camY, float camZ,
      Matrix4f viewProj, float cameraPitch, int altAboveGround) {
    if (!this.extendedLodInitialized || this.lodRingTracker == null)
      return;
    MinecraftClient mc = MinecraftClient.getInstance();
    if (mc != null && mc.world != null) {
      int minY = mc.world.getBottomY() >> 4;
      int maxY = (mc.world.getTopYInclusive() + 1) >> 4;
      this.lodRingTracker.setYBounds(minY, maxY);
    }
    LodRingTracker.UpdateDelta delta = this.lodRingTracker.update(
        (int) camX, (int) camY, (int) camZ);

    if (delta.toLoad.length > 0 && MetalRenderConfig.extremeRenderDistance() > 32
        && DistantChunkLoader.getInstance().isRunning()) {
      DistantChunkLoader.getInstance().requestSections(delta.toLoad);
    }

    if (this.extendedLodRenderer != null && this.extendedLodRenderer.isInitialized()
        && this.lodChunkStorage != null) {
      if (delta.cameraMoved) {
        this.extendedLodRenderer.processRingDelta(delta, this.lodRingTracker,
            this.lodChunkStorage);
      }
      long now = System.nanoTime();
      long frameTimeNs = (this.lastFrameNanoTime > 0) ? (now - this.lastFrameNanoTime) : 16_000_000L;
      this.lastFrameNanoTime = now;
      int draws = this.extendedLodRenderer.renderFrame(viewProj, camX, camY, camZ,
          cameraPitch, altAboveGround, frameTimeNs);
      if (this.frameCount < 5 || this.frameCount % 600 == 0) {
        MetalLogger.info("[ExtendedLOD] %s", this.extendedLodRenderer.getDebugInfo());
      }
    }

    if (delta.cameraMoved && delta.toUnload.length > 0 && this.frameCount % 300 == 0) {
      MetalLogger.info("[ExtendedLOD] Ring update: +{} load, -{} unload, {} active",
          delta.toLoad.length, delta.toUnload.length,
          this.lodRingTracker.getActiveSectionCount());
    }
  }

  private void shutdownExtendedLod() {
    if (this.extendedLodRenderer != null) {
      this.extendedLodRenderer.destroy();
      this.extendedLodRenderer = null;
    }

    LodChunkIngestor ingestor = LodChunkIngestor.getInstance();
    if (ingestor.isRunning()) {
      ingestor.shutdown();
    }

    DistantChunkLoader distantLoader = DistantChunkLoader.getInstance();
    if (distantLoader.isRunning()) {
      distantLoader.shutdown();
    }

    if (this.lodChunkStorage != null) {
      this.lodChunkStorage.close();
      this.lodChunkStorage = null;
    }

    if (this.lodRingTracker != null) {
      this.lodRingTracker.clear();
      this.lodRingTracker = null;
    }

    this.extendedLodInitialized = false;
  }

  public LodChunkStorage getLodChunkStorage() {
    return this.lodChunkStorage;
  }

  public boolean isExtendedLodActive() {
    return this.extendedLodInitialized;
  }
}
