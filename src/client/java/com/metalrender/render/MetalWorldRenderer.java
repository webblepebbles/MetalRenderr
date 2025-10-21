package com.metalrender.render;

import com.metalrender.MetalRenderClient;
import com.metalrender.config.MetalRenderConfig;
import com.metalrender.nativebridge.NativeBridge;
import com.metalrender.performance.RenderOptimizer;
import com.metalrender.sodium.backend.MeshShaderBackend;
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
  private MeshShaderBackend cachedMeshBackend;
  private int lastWidth = 16;
  private int lastHeight = 16;
  private float lastScale = 1.0F;
  private float lastMetalFXScale = 1.0F;
  private long lastMetalFXConfigTime = 0L;
  private static final long METALFX_CONFIG_COOLDOWN_MS = 100L;
  private double lastCameraX = 0.0;
  private double lastCameraZ = 0.0;
  private float motionVectorX = 0.0F;
  private float motionVectorY = 0.0F;

  public long getNativeHandle() { return this.handle; }

  public int getRenderWidth() { return this.lastWidth; }

  public int getRenderHeight() { return this.lastHeight; }

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

        boolean metalFXEnabled = MetalRenderConfig.metalFXEnabled() &&
                                 NativeBridge.nSupportsMetalFX();
        float metalFXScale = MetalRenderConfig.metalFXScale();
        long currentTime = System.currentTimeMillis();
        if (metalFXEnabled &&
            (Math.abs(metalFXScale - this.lastMetalFXScale) > 0.001F) &&
            (currentTime - this.lastMetalFXConfigTime) >=
                METALFX_CONFIG_COOLDOWN_MS) {
          try {
            NativeBridge.nConfigureMetalFX(this.handle, this.lastWidth,
                                           this.lastHeight, metalFXScale);
            this.lastMetalFXScale = metalFXScale;
            this.lastMetalFXConfigTime = currentTime;
            MetalLogger.info(
                "MetalFX configured: scale=" +
                String.format("%.2f%%", metalFXScale * 100) +
                " output=" + this.lastWidth + "x" + this.lastHeight +
                " input=" + (int)(this.lastWidth * metalFXScale) + "x" +
                (int)(this.lastHeight * metalFXScale));
          } catch (Throwable ex) {
            MetalLogger.error("Failed to configure MetalFX: " +
                              ex.getMessage());
          }
        }

        viewProjMatrix.get(this.frameViewProjection);

        double cameraDeltaX = camera.getPos().x - this.lastCameraX;
        double cameraDeltaZ = camera.getPos().z - this.lastCameraZ;
        this.motionVectorX = (float)cameraDeltaX;
        this.motionVectorY = (float)cameraDeltaZ;
        this.lastCameraX = camera.getPos().x;
        this.lastCameraZ = camera.getPos().z;

        NativeBridge.nBeginFrame(this.handle, this.frameViewProjection, null,
                                 this.motionVectorX, this.motionVectorY);
        NativeBridge.nClearIndirectCommands(this.handle);

        MeshShaderBackend backend = this.meshBackend();
        int queued = 0;
        if (backend != null) {
          queued = backend.emitDraws(this.handle, this.renderOptimizer, camera);
        }
        NativeBridge.nExecuteIndirect(this.handle, queued);
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

  public void onChunksProcessed() {}

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

    if (this.handle != 0L) {
      NativeBridge.nDestroy(this.handle);
      this.handle = 0L;
    }
    this.ready = false;
  }

  public void updateParallelEncoding(boolean enabled) {
    if (this.handle != 0L) {
      NativeBridge.nSetParallelEncoding(this.handle, enabled);
      MetalLogger.info("Parallel encoding updated: " + enabled);
    }
  }
}
