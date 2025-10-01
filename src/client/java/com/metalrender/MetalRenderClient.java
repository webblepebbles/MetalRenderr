package com.metalrender;

import com.metalrender.config.MetalRenderConfig;
import com.metalrender.sodium.backend.MeshShaderBackend;
import com.metalrender.sodium.backend.MetalRendererBackend;
import com.metalrender.util.MetalLogger;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import java.util.concurrent.atomic.AtomicBoolean;

@Environment(EnvType.CLIENT)
public class MetalRenderClient implements ClientModInitializer{
    private static MetalRendererBackend fallbackBackend;
    private static MeshShaderBackend meshBackend;
    private static boolean usingMesh = false;
    private static int ticksElapsed = 0;

    @Override // Theres already a check for GLFW here so i dont think that the 1 minute timer is necessary
    public void onInitializeClient() {
        MetalLogger.info("scheduling MetalRender client initialization after window is ready");
        AtomicBoolean initialized = new AtomicBoolean(false);
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            try {
                if (initialized.get()) return;
                long ctx = org.lwjgl.glfw.GLFW.glfwGetCurrentContext();
                if (ctx == 0L) return;
                ticksElapsed++;
                if (ticksElapsed < 20 * 60) { 
                    return;
                }
                initialized.set(true);
                MetalLogger.info("starting MetalRenderClient (deferred, after delay)");
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