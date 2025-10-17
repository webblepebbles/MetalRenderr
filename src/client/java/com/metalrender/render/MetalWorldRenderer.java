package com.metalrender.render;

import com.metalrender.config.MetalRenderConfig;
import com.metalrender.nativebridge.NativeBridge;
import com.metalrender.performance.PerformanceController;
import com.metalrender.performance.RenderOptimizer;
import com.metalrender.util.MetalLogger;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import org.joml.Matrix4f;

public class MetalWorldRenderer {
   private long handle;
   private volatile boolean ready;
   private final RenderOptimizer renderOptimizer = RenderOptimizer.getInstance();
   private int lastWidth = 16;
   private int lastHeight = 16;
   private float lastScale = 1.0F;

   public MetalWorldRenderer() {
      MetalLogger.info("MetalWorldRenderer created (initializing native backend)");

      try {
         if (NativeBridge.isLibLoaded() && NativeBridge.nIsAvailable()) {
            this.handle = NativeBridge.nInit(16, 16, 1.0F);
            this.ready = this.handle != 0L;
            MetalLogger.info("Native backend {} (device='{}')", this.ready ? "ready" : "failed", NativeBridge.nGetDeviceName());
         } else {
            this.ready = false;
            MetalLogger.warn("Native library not loaded or unavailable");
         }
      } catch (Throwable var2) {
         this.ready = false;
         MetalLogger.error("Failed to initialize native backend", var2);
      }

   }

   public boolean isReady() {
      return this.ready;
   }

   public void renderFrame(Object viewport, Object matrices, double x, double y, double z) {
      if (this.ready && this.handle != 0L) {
         try {
            MinecraftClient mc = MinecraftClient.getInstance();
            Camera camera = mc.gameRenderer.getCamera();
            Matrix4f viewProjMatrix = (new Matrix4f()).identity();
            if (MetalRenderConfig.aggressiveFrustumCulling()) {
               this.renderOptimizer.updateFrame(camera, viewProjMatrix);
            }

            float scale = MetalRenderConfig.resolutionScale();
            if (Math.abs(scale - this.lastScale) > 0.001F) {
               NativeBridge.nResize(this.handle, this.lastWidth, this.lastHeight, scale);
               this.lastScale = scale;
            }

            NativeBridge.nBeginFrame(this.handle, (float[])null, (float[])null, 0.0F, 1.0F);
            RenderOptimizer.PerformanceStats stats = this.renderOptimizer.getFrameStats();
            int drawn = Math.max(0, stats.totalChunks - stats.frustumCulled - stats.occlusionCulled);
            PerformanceController.accumulateChunkStats(stats.totalChunks, drawn, stats.frustumCulled, stats.occlusionCulled);
         } catch (Throwable var15) {
            MetalLogger.error("renderFrame failed", var15);
         }

      }
   }

   public void uploadBuildResult(Object result) {
   }
}
