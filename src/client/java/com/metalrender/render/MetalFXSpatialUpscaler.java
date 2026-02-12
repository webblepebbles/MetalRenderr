package com.metalrender.render;

import com.metalrender.config.MetalRenderConfig;
import com.metalrender.nativebridge.NativeBridge;
import com.metalrender.util.MetalLogger;

public class MetalFXSpatialUpscaler {

    private final long deviceHandle;
    private boolean initialized = false;
    private int renderWidth;
    private int renderHeight;
    private int outputWidth;
    private int outputHeight;
    private float currentScale;

    public MetalFXSpatialUpscaler(long deviceHandle) {
        this.deviceHandle = deviceHandle;
    }

    public boolean initialize(int nativeWidth, int nativeHeight) {
        if (deviceHandle == 0L)
            return false;

        float scale = MetalRenderConfig.metalFXSpatialScale();
        this.outputWidth = nativeWidth;
        this.outputHeight = nativeHeight;
        this.renderWidth = Math.max(1, (int) (nativeWidth * scale));
        this.renderHeight = Math.max(1, (int) (nativeHeight * scale));
        this.currentScale = scale;

        try {
            boolean ok = NativeBridge.nInitMetalFXSpatial(deviceHandle,
                    renderWidth, renderHeight, outputWidth, outputHeight);
            if (ok) {
                initialized = true;
                MetalLogger.info("[MetalFXSpatial] Initialized: render {}x{} → output {}x{} (scale={:.2f})",
                        renderWidth, renderHeight, outputWidth, outputHeight, scale);
            } else {
                MetalLogger.warn("[MetalFXSpatial] MetalFX Spatial not supported on this device");
            }
            return ok;
        } catch (Throwable e) {
            MetalLogger.error("[MetalFXSpatial] Init failed: {}", e.getMessage());
            return false;
        }
    }

    public boolean needsResize(int nativeWidth, int nativeHeight) {
        if (!initialized)
            return true;
        float scale = MetalRenderConfig.metalFXSpatialScale();
        return nativeWidth != outputWidth || nativeHeight != outputHeight
                || Math.abs(scale - currentScale) > 0.001f;
    }

    public void resize(int nativeWidth, int nativeHeight) {
        if (initialized) {
            destroy();
        }
        initialize(nativeWidth, nativeHeight);
    }

    public int getRenderWidth() {
        return initialized ? renderWidth : outputWidth;
    }

    public int getRenderHeight() {
        return initialized ? renderHeight : outputHeight;
    }

    public void apply() {
        if (!initialized || deviceHandle == 0L)
            return;

        try {
            NativeBridge.nApplyMetalFXSpatial(deviceHandle);
        } catch (Throwable e) {
            MetalLogger.error("[MetalFXSpatial] Apply failed: {}", e.getMessage());
        }
    }

    public boolean isActive() {
        return initialized && MetalRenderConfig.metalFXSpatialEnabled();
    }

    public void destroy() {
        if (initialized && deviceHandle != 0L) {
            NativeBridge.nDestroyMetalFXSpatial(deviceHandle);
        }
        initialized = false;
    }
}
