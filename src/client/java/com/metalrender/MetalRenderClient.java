package com.metalrender;

import com.metalrender.config.MetalRenderConfig;
import com.metalrender.nativebridge.MetalHardwareChecker;
import com.metalrender.util.MetalLogger;
import net.fabricmc.api.ClientModInitializer;

public final class MetalRenderClient implements ClientModInitializer {
    private static volatile boolean ENABLED = false;
    private static volatile com.metalrender.render.MetalWorldRenderer WORLD;
    private static volatile com.metalrender.sodium.backend.MeshShaderBackend MESH_BACKEND;

    @Override
    public void onInitializeClient() {
        MetalRenderConfig.loadFromSystemProperties();
        ENABLED = MetalHardwareChecker.isCompatible();
        if (ENABLED) {
            WORLD = new com.metalrender.render.MetalWorldRenderer();
            MESH_BACKEND = new com.metalrender.sodium.backend.MeshShaderBackend();
            MetalLogger.info("MetalRender initialized (Sodium integration active)");
        } else {
            MetalLogger.warn("MetalRender disabled due to incompatible hardware");
        }
    }

    public static boolean isEnabled() {
        return ENABLED;
    }

    public static com.metalrender.render.MetalWorldRenderer getWorldRenderer() {
        return WORLD;
    }

    public static com.metalrender.sodium.backend.MeshShaderBackend getMeshBackend() {
        return MESH_BACKEND;
    }
}
