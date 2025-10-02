package com.metalrender;

import com.metalrender.config.MetalRenderConfig;
import com.metalrender.sodium.backend.MeshShaderBackend;
import com.metalrender.sodium.backend.MetalRendererBackend;
import com.metalrender.util.MetalLogger;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import java.util.concurrent.atomic.AtomicBoolean;

@Environment(EnvType.CLIENT)
public class MetalRenderClient implements ClientModInitializer{
    private static MetalRendererBackend fallbackBackend;
    private static MeshShaderBackend meshBackend;
    private static boolean usingMesh = false;
    private static int ticksElapsed = 0;

    @Override 
    public void onInitializeClient() {
        MetalLogger.info("scheduling MetalRender client initialization on CLIENT_STARTED");
        AtomicBoolean initialized = new AtomicBoolean(false);
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            if (initialized.getAndSet(true)) return;
            try {
                MetalLogger.info("starting MetalRenderClient (CLIENT_STARTED)");
                String osName = System.getProperty("os.name").toLowerCase();
                if (!osName.contains("mac")) {
                    MetalLogger.warn("MetalRender: Your computer does not support Metal.");
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
            } catch (Throwable t) {
                MetalLogger.error("Error during MetalRender client init: " + t);
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