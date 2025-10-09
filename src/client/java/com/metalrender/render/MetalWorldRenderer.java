package com.metalrender.render;

import com.metalrender.config.MetalRenderConfig;
import com.metalrender.nativebridge.NativeBridge;
import com.metalrender.performance.DynamicScaler;
import com.metalrender.performance.RenderOptimizer;
import com.metalrender.util.MetalLogger;
import com.metalrender.util.PerformanceLogger;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import org.joml.Matrix4f;

public class MetalWorldRenderer {
    private long handle;
    private volatile boolean ready;
    private final RenderOptimizer renderOptimizer = RenderOptimizer.getInstance();
    private final PerformanceLogger perfLogger = new PerformanceLogger();
    private final DynamicScaler dynamicScaler = new DynamicScaler();
    private int lastWidth = 16, lastHeight = 16;
    private float lastScale = 1.0f;

    public MetalWorldRenderer() {
        MetalLogger.info("MetalWorldRenderer created (initializing native backend)");
        try {
            if (NativeBridge.isLibLoaded() && NativeBridge.nIsAvailable()) {
                // Use a small dummy size; we don't have the real viewport here.
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

    // Accept Object types to avoid compile-time dependency on Sodium
    public void renderFrame(Object viewport, Object matrices, double x, double y, double z) {
        if (!ready || handle == 0L)
            return;

        perfLogger.startFrame();

        try {
            // Get camera and view-projection matrix for culling
            MinecraftClient mc = MinecraftClient.getInstance();
            Camera camera = mc.gameRenderer.getCamera();

            // Create a dummy view-projection matrix (in real implementation, extract from matrices param)
            Matrix4f viewProjMatrix = new Matrix4f().identity();

            // Update render optimizer with frustum culling enabled by default
            if (MetalRenderConfig.aggressiveFrustumCulling()) {
                renderOptimizer.updateFrame(camera, viewProjMatrix);
            }

            // Apply dynamic scaling: resize native surface if resolution scale changed
            float scale = MetalRenderConfig.resolutionScale();
            if (Math.abs(scale - lastScale) > 1e-3f) {
                // We don't know real window size here; keep dummy size but update scale for native
                NativeBridge.nResize(handle, lastWidth, lastHeight, scale);
                lastScale = scale;
            }

            // For now we issue a minimal frame sequence; matrices are not decoded yet.
            NativeBridge.nBeginFrame(handle, null, null, 0.0f, 1.0f);

            // TODO: Implement actual chunk visibility testing here
            // Example: Loop through visible chunks and test with renderOptimizer.shouldRenderChunk()

            // Terrain dispatch currently disabled until real geometry path is implemented
            // Keep per-frame overhead minimal

            // Get performance stats
            RenderOptimizer.PerformanceStats stats = renderOptimizer.getFrameStats();
            int drawn = Math.max(0, stats.totalChunks - stats.frustumCulled - stats.occlusionCulled);
            perfLogger.endFrame(stats.totalChunks, drawn, stats.frustumCulled, stats.occlusionCulled);

            // Feed scaler after frame to adjust toward target time
            dynamicScaler.onFrameEnd(perfLogger.getAvgFrameTime());

        } catch (Throwable t) {
            MetalLogger.error("renderFrame failed", t);
        }
    }

    public void uploadBuildResult(Object result) {
        // Placeholder: convert Sodium build output into our buffer arena
    }
}
