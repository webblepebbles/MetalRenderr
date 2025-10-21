package com.metalrender.render;

import com.metalrender.MetalRenderClient;
import com.metalrender.config.MetalRenderConfig;
import com.metalrender.nativebridge.NativeBridge;
import com.metalrender.performance.PerformanceController;
import com.metalrender.performance.RenderOptimizer;
import com.metalrender.sodium.backend.MeshShaderBackend;
import com.metalrender.temporal.TemporalAA;
import com.metalrender.temporal.TemporalUpscaler;
import com.metalrender.util.MetalLogger;
import com.metalrender.util.PersistentBufferArena;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import org.joml.Matrix4f;

public class MetalWorldRenderer {
  private long handle;
  private volatile boolean ready;
  private final RenderOptimizer renderOptimizer = RenderOptimizer.getInstance();
  private final PersistentBufferArena persistentArena =
      new PersistentBufferArena();
  private final float[] frameViewProjection = new float[16];
  private final TemporalAA temporalAA = new TemporalAA();
  private final TemporalUpscaler temporalUpscaler = new TemporalUpscaler();
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

  public boolean isReady() { return this.ready; }

  public void renderFrame(Object viewport, Object matrices, double x, double y,
                          double z) {
    if (this.ready && this.handle != 0L) {
      try {
        MinecraftClient mc = MinecraftClient.getInstance();
        Camera camera = mc.gameRenderer.getCamera();
        int framebufferWidth =
            Math.max(1, mc.getWindow().getFramebufferWidth());
        int framebufferHeight =
            Math.max(1, mc.getWindow().getFramebufferHeight());
        this.temporalUpscaler.updateScale();
        if (framebufferWidth != this.lastWidth ||
            framebufferHeight != this.lastHeight) {
          NativeBridge.nResize(this.handle, framebufferWidth, framebufferHeight,
                               this.lastScale);
          this.lastWidth = framebufferWidth;
          this.lastHeight = framebufferHeight;
        }

        Matrix4f projection = new Matrix4f().setPerspective(
            this.getFovRadians(mc),
            (float)framebufferWidth / (float)framebufferHeight, 0.05F, 512.0F);
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
        NativeBridge.nBeginFrame(this.handle, this.frameViewProjection, null,
                                 0.0F, 1.0F);
        NativeBridge.nClearIndirectCommands(this.handle);

        MeshShaderBackend backend = this.meshBackend();
        int queued = 0;
        if (backend != null) {
          queued = backend.emitDraws(this.handle, this.renderOptimizer, camera);
        }
        RenderOptimizer.PerformanceStats stats =
            this.renderOptimizer.getFrameStats();
        int drawn = Math.max(0, stats.totalChunks - stats.frustumCulled -
                                    stats.occlusionCulled);
        PerformanceController.accumulateChunkStats(stats.totalChunks, drawn,
                                                   stats.frustumCulled,
                                                   stats.occlusionCulled);
        NativeBridge.nExecuteIndirect(this.handle, queued);
        this.renderOptimizer.finalizeFrame();
      } catch (Throwable var15) {
        MetalLogger.error("renderFrame failed", var15);
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
    float pitch = (float)Math.toRadians(camera.getPitch());
    float yaw = (float)Math.toRadians(camera.getYaw() + 180.0F);
    view.rotateY(-yaw);
    view.rotateX(-pitch);
    net.minecraft.util.math.Vec3d pos = camera.getPos();
    view.translate((float)-pos.x, (float)-pos.y, (float)-pos.z);
    return view;
  }

  private float getFovRadians(MinecraftClient client) {
    double fov = 70.0D;
    try {
      fov = client.options.getFov().getValue();
    } catch (Throwable ignored) {
    }
    return (float)Math.toRadians(fov);
  }

  public void destroy() {
    this.cachedMeshBackend = null;
    if (this.pipelineCache != null) {
      this.pipelineCache.reset();
      this.pipelineCache = null;
    }
    if (this.handle != 0L) {
      NativeBridge.nDestroy(this.handle);
      this.handle = 0L;
    }
    this.ready = false;
  }
}
