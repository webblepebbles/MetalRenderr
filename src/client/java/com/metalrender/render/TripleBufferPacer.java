package com.metalrender.render;

import com.metalrender.config.MetalRenderConfig;
import com.metalrender.nativebridge.NativeBridge;
import com.metalrender.util.MetalLogger;

public class TripleBufferPacer {

    public static final int MAX_FRAMES_IN_FLIGHT = 3;

    private final long deviceHandle;
    private boolean enabled = false;
    private int currentFrameIndex = 0;

    public TripleBufferPacer(long deviceHandle) {
        this.deviceHandle = deviceHandle;
    }

    public void setEnabled(boolean enabled) {
        if (this.enabled == enabled)
            return;
        this.enabled = enabled;

        if (deviceHandle != 0L) {
            NativeBridge.nSetTripleBuffering(deviceHandle, enabled);
            MetalLogger.info("[TripleBuffer] {} (max {} frames in flight)",
                    enabled ? "Enabled" : "Disabled", enabled ? MAX_FRAMES_IN_FLIGHT : 2);
        }
    }

    public int beginFrame() {
        if (!enabled || deviceHandle == 0L) {
            return 0;
        }

        currentFrameIndex = NativeBridge.nGetCurrentFrameIndex(deviceHandle);
        return currentFrameIndex;
    }

    public int getCurrentFrameIndex() {
        return enabled ? currentFrameIndex : 0;
    }

    public long getBufferOffset(long perFrameSize) {
        return (long) currentFrameIndex * perFrameSize;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void syncFromConfig() {
        setEnabled(MetalRenderConfig.tripleBufferingEnabled());
    }
}
