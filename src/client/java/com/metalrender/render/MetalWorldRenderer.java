package com.metalrender.render;

import com.metalrender.MetalRenderClient;
import com.metalrender.config.MetalRenderConfig;
import com.metalrender.nativebridge.NativeBridge;
import com.metalrender.performance.PerformanceController;
import com.metalrender.performance.RenderOptimizer;
import com.metalrender.render.atlas.CapturedAtlas;
import com.metalrender.render.atlas.CapturedAtlasRepository;
import com.metalrender.render.MetalSurfaceManager;
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
  private MeshShaderBackend cachedMeshBackend;
  private PipelineCache pipelineCache;
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
          // Set shaders path for terrain rendering
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
    // Try common locations for the metallib file
    String[] possiblePaths = {
        // Development path
        "src/main/resources/shaders.metallib",
        // Runtime paths
        "shaders.metallib",
        System.getProperty("user.dir") + "/shaders.metallib",
    };

    // First try to extract from classpath
    try {
      java.io.InputStream stream = MetalWorldRenderer.class.getClassLoader()
          .getResourceAsStream("shaders.metallib");
      if (stream != null) {
        // Extract to temp file
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

    // Try filesystem paths
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
        // Upload atlas texture if needed
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

        // Ensure CAMetalLayer is attached for direct presentation (every frame until
        // successful)
        MetalSurfaceManager.ensureSurface(this.handle);

        Matrix4f projection = new Matrix4f().setPerspective(
            this.getFovRadians(mc),
            (float) framebufferWidth / (float) framebufferHeight, 0.05F, 512.0F);

        // Fix for Metal clip space (0 to 1) vs OpenGL (-1 to 1)
        // We need to map [-1, 1] to [0, 1]
        // z_metal = 0.5 * z_gl + 0.5
        // Matrix: Translate(0, 0, 0.5) * Scale(1, 1, 0.5)
        Matrix4f correction = new Matrix4f().translation(0, 0, 0.5f).scale(1, 1, 0.5f);
        // Apply correction: P_new = Correction * P_old
        // Since JOML mul is (this * right), we do correction.mul(projection,
        // projection)
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

        // Pass actual camera position for terrain rendering
        Vec3d cameraPos = camera.getCameraPos();
        float[] cameraPosArray = new float[] {
            (float) cameraPos.x,
            (float) cameraPos.y,
            (float) cameraPos.z
        };

        NativeBridge.nBeginFrame(this.handle, this.frameViewProjection, cameraPosArray,
            0.0F, 512.0F); // fogStart=0, fogEnd=512 (render distance)
        NativeBridge.nClearIndirectCommands(this.handle);

        MeshShaderBackend backend = this.meshBackend();
        int queued = 0;
        int meshCount = 0;
        if (backend != null) {
          meshCount = backend.getMeshCount();
          queued = backend.emitDraws(this.handle, this.persistentArena, this.renderOptimizer, camera);
        }

        // Log first few frames AND every 60 frames after (for ongoing debug)
        if (this.frameCount < 10 || this.frameCount % 60 == 0) {
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

        // Don't blit here - we blit at flip time so entities render first to GL
        // Then at flip time we composite Metal terrain UNDER the entities

        this.renderedThisFrame = true;
        this.frameCount++;
      } catch (Throwable var15) {
        MetalLogger.error("renderFrame failed", var15);
      }
    }
  }

  /**
   * Render frame using Sodium's actual matrices.
   * This is the KEY fix - use the EXACT same matrices Sodium uses!
   */
  public void renderFrameWithMatrices(Matrix4fc projection, Matrix4fc modelView, double x, double y, double z) {
    if (this.ready && this.handle != 0L) {
      try {
        // Upload atlas texture if needed
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

        // Use Sodium's matrices directly!
        // Compute viewProj = projection * modelView (Sodium's style)
        Matrix4f viewProjMatrix = new Matrix4f();
        projection.mul(modelView, viewProjMatrix);

        // TEST 53: Validate matrices - skip if invalid (Infinity/NaN)
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

        // TEST 53: Log matrices for first few valid frames
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
          // Return early - don't render with invalid matrices
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

        // Pass actual camera position for terrain rendering
        float[] cameraPosArray = new float[] {
            (float) x,
            (float) y,
            (float) z
        };

        if (this.frameCount < 5) {
          MetalLogger.info("[MetalRender] Camera pos from Sodium: (%.2f, %.2f, %.2f)", x, y, z);
        }

        NativeBridge.nBeginFrame(this.handle, this.frameViewProjection, cameraPosArray,
            0.0F, 512.0F); // fogStart=0, fogEnd=512
        NativeBridge.nClearIndirectCommands(this.handle);

        MeshShaderBackend backend = this.meshBackend();
        int queued = 0;
        int meshCount = 0;
        if (backend != null) {
          meshCount = backend.getMeshCount();
          queued = backend.emitDraws(this.handle, this.persistentArena, this.renderOptimizer, camera);
        }

        if (this.frameCount < 10 || this.frameCount % 60 == 0) {
          MetalLogger.info("[MetalRender] Frame %d: queued %d draw commands from %d meshes",
              this.frameCount, queued, meshCount);
        }

        RenderOptimizer.PerformanceStats stats = this.renderOptimizer.getFrameStats();
        int drawn = Math.max(0, stats.totalChunks - stats.frustumCulled - stats.occlusionCulled);
        PerformanceController.accumulateChunkStats(stats.totalChunks, drawn,
            stats.frustumCulled, stats.occlusionCulled);

        if (this.frameCount < 5) {
          MetalLogger.info("[MetalRender] Frame %d: calling nExecuteIndirect with %d commands", this.frameCount,
              queued);
        }
        NativeBridge.nExecuteIndirect(this.handle, queued);

        if (this.frameCount < 5) {
          MetalLogger.info("[MetalRender] Frame %d: calling nDrawTerrain", this.frameCount);
        }
        NativeBridge.nDrawTerrain(this.handle, 0);

        if (this.frameCount < 5) {
          MetalLogger.info("[MetalRender] Frame %d: nDrawTerrain returned", this.frameCount);
        }
        this.renderOptimizer.finalizeFrame();

        this.renderedThisFrame = true;
        this.frameCount++;
      } catch (Throwable var15) {
        MetalLogger.error("renderFrameWithMatrices failed", var15);
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

  /**
   * Force blit immediately regardless of blittedThisFrame flag.
   * Called from Sodium hook to ensure terrain is in the current framebuffer.
   * 
   * The blit happens to whatever FBO is currently bound at the injection site.
   * Sodium/Minecraft should have the correct FBO bound at this point.
   */
  public void forceBlitNow() {
    if (this.ready && this.handle != 0L) {
      NativeBridge.nWaitForRender(this.handle);

      int currentFbo = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_BINDING);
      int[] viewport = new int[4];
      org.lwjgl.opengl.GL11.glGetIntegerv(org.lwjgl.opengl.GL11.GL_VIEWPORT, viewport);

      if (this.frameCount % 60 == 0 || this.frameCount < 100) {
        MetalLogger.info("[MetalRender] forceBlitNow: currentFBO=%d, Viewport=[%d,%d,%d,%d]",
            currentFbo, viewport[0], viewport[1], viewport[2], viewport[3]);
      }

      this.ioSurfaceBlitter.blit(this.handle);
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
      // Atlas not captured yet
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
    // Placeholder - sodiumless mode configuration
  }
}
