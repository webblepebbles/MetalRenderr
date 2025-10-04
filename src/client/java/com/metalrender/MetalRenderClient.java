package com.metalrender;

import com.metalrender.config.MetalRenderConfig;
import com.metalrender.sodium.backend.MeshShaderBackend;
import com.metalrender.sodium.backend.MetalRendererBackend;
import com.metalrender.util.MetalLogger;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;

public class MetalRenderClient implements ClientModInitializer {
    private static MetalRendererBackend fallbackBackend;
    private static MeshShaderBackend meshBackend;
    private static boolean usingMesh = false;
    private static int ticksElapsed = 0;

    @Override
    public void onInitializeClient() {
        MetalLogger.info("scheduling MetalRender client initialization on CLIENT_STARTED");
        AtomicBoolean initialized = new AtomicBoolean(false);
        boolean forceFallback = false; // you can set this to true to load it regularly using mesh shaders
                                       // if applicable. T
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            if (initialized.getAndSet(true))
                return;
            try {
                MetalLogger.info("starting MetalRenderClient (CLIENT_STARTED)");
                String osName = System.getProperty("os.name").toLowerCase();
                if (!osName.contains("mac")) {
                    MetalLogger.warn("MetalRender: Your computer does not support Metal.");
                    return;
                }
                if (forceFallback) {
                    MetalLogger.info("Force fallback: using MetalRendererBackend for testing");
                    initFallback();
                    return;
                }
                MetalRenderConfig cfg = MetalRenderConfig.get();
                if (cfg.getAdvancedMetalFeatures().isMeshShadersEnabled()) {
                    MetalLogger.info("Mesh shaders added via config");
                    meshBackend = new MeshShaderBackend();
                    if (meshBackend.initIfNeeded() && meshBackend.isMeshEnabled()) {
                        usingMesh = true;
                        MetalLogger.info("MeshShaderBackend on");
                    } else {
                        MetalLogger.warn("Mesh shaders are not available, using MetalRendererBackend");
                        meshBackend = null;
                        initFallback();
                    }
                } else {
                    initFallback();
                }
            } catch (UnsatisfiedLinkError | IllegalArgumentException e) {
                MetalLogger.error("Error during MetalRender client init: " + e);
            }
        });
    }

    private void initFallback() {
        fallbackBackend = new MetalRendererBackend();
        if (fallbackBackend.initIfNeeded()) {
            usingMesh = false;
            MetalLogger.info("Fallback MetalRendererBackend active");
        } else {
            MetalLogger.error("Failed to initialize MetalRendererBackend!");
        }
    }

    public static boolean isUsingMeshShaders() {
        return usingMesh;
    }

    public static MeshShaderBackend getMeshBackend() {
        return meshBackend;
    }

    public static MetalRendererBackend getFallbackBackend() {
        return fallbackBackend;
    }
}
