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
    private int lastWidth = 16, lastHeight = 16;
    private float lastScale = 1.0f;

    public MetalWorldRenderer() {
        MetalLogger.info("MetalWorldRenderer created (initializing native backend)");
        try {
            if (NativeBridge.isLibLoaded() && NativeBridge.nIsAvailable()) {
                handle = NativeBridge.nInit(16, 16, 1.0f);
                ready = handle != 0L;
                MetalLogger.info(
                    "Native backend {} (device='{}')", ready ? "ready" : "failed", NativeBridge.nGetDeviceName());
            } else {
                ready = false;
                MetalLogger.warn("Native library not loaded or unavailable");
            }
        } catch (Throwable t) {
            ready = false;
            MetalLogger.error("Failed to initialize native backend", t);
        }
    }

    public boolean isReady() {
        return ready;
    }
    public void renderFrame(Object viewport, Object matrices, double x, double y, double z) {
        if (!ready || handle == 0L)
            return;

        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            Camera camera = mc.gameRenderer.getCamera();

            Matrix4f viewProjMatrix = new Matrix4f().identity();
            if (MetalRenderConfig.aggressiveFrustumCulling()) {
                renderOptimizer.updateFrame(camera, viewProjMatrix);
            }
            float scale = MetalRenderConfig.resolutionScale();
            if (Math.abs(scale - lastScale) > 1e-3f) {
                NativeBridge.nResize(handle, lastWidth, lastHeight, scale);
                lastScale = scale;
            }
            NativeBridge.nBeginFrame(handle, null, null, 0.0f, 1.0f);

            RenderOptimizer.PerformanceStats stats = renderOptimizer.getFrameStats();
            int drawn = Math.max(0, stats.totalChunks - stats.frustumCulled - stats.occlusionCulled);
            PerformanceController.accumulateChunkStats(
                stats.totalChunks, drawn, stats.frustumCulled, stats.occlusionCulled);

        } catch (Throwable t) {
            MetalLogger.error("renderFrame failed", t);
        }
    }

    public void uploadBuildResult(Object result) {
    }
}
