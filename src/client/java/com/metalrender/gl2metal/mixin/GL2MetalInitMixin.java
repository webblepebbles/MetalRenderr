package com.metalrender.gl2metal.mixin;

import com.metalrender.gl2metal.GL2MetalConfig;
import com.metalrender.gl2metal.GL2MetalManager;
import com.metalrender.gl2metal.GL2MetalTranslator;
import com.metalrender.util.MetalLogger;
import net.minecraft.client.MinecraftClient;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin to initialize the GL2Metal system when the game starts.
 * 
 * GL→Metal Interception Mode:
 * - Intercepts all OpenGL calls via GL*Mixins
 * - Translates them to Metal via GL2MetalTranslator
 * - Renders to a Metal window that floats over GLFW window
 * - GLFW window receives input, Metal window displays output
 * 
 * IMPORTANT: If no interception categories are enabled, the Metal window
 * is NOT created to avoid interfering with OpenGL operations.
 */
@Mixin(MinecraftClient.class)
public class GL2MetalInitMixin {

    private static boolean gl2metalInitAttempted = false;
    private static int lastWidth = 0;
    private static int lastHeight = 0;
    private static boolean lastMinimized = false;

    /**
     * Initialize GL2Metal after the window is created but before the game loop
     * starts.
     */
    @Inject(method = "run", at = @At("HEAD"))
    private void metalrender$initGL2Metal(CallbackInfo ci) {
        if (gl2metalInitAttempted)
            return;
        gl2metalInitAttempted = true;

        // Check if GL2Metal mode is requested via config/system property
        boolean enableGL2Metal = Boolean.getBoolean("metalrender.gl2metal");

        if (!enableGL2Metal) {
            MetalLogger.info(
                    "[GL2MetalInitMixin] GL2Metal mode not requested (use -Dmetalrender.gl2metal=true to enable)");
            return;
        }

        // Check if ANY interception is actually enabled
        // If not, skip Metal window creation to avoid interfering with OpenGL
        if (!GL2MetalConfig.isAnyInterceptionEnabled()) {
            MetalLogger.info(
                    "[GL2MetalInitMixin] GL2Metal mode enabled but NO interception categories active");
            MetalLogger.info(
                    "[GL2MetalInitMixin] Skipping Metal window creation (enable categories with -Dmetalrender.gl2metal.drawcalls=true etc.)");
            return;
        }

        MinecraftClient client = (MinecraftClient) (Object) this;
        int width = client.getWindow().getFramebufferWidth();
        int height = client.getWindow().getFramebufferHeight();
        lastWidth = width;
        lastHeight = height;

        MetalLogger.info("[GL2MetalInitMixin] Initializing GL2Metal interception with window size {}x{}", width,
                height);
        MetalLogger.info("[GL2MetalInitMixin] Active categories: drawCalls={}, state={}, buffers={}, textures={}, shaders={}, fbos={}, vaos={}",
                GL2MetalConfig.INTERCEPT_DRAW_CALLS, GL2MetalConfig.INTERCEPT_STATE,
                GL2MetalConfig.INTERCEPT_BUFFERS, GL2MetalConfig.INTERCEPT_TEXTURES,
                GL2MetalConfig.INTERCEPT_SHADERS, GL2MetalConfig.INTERCEPT_FBOS,
                GL2MetalConfig.INTERCEPT_VAOS);

        if (GL2MetalManager.initialize(width, height)) {
            MetalLogger.info("[GL2MetalInitMixin] GL2Metal initialized, enabling OpenGL interception");
            GL2MetalManager.enable();

            // Do initial sync of Metal window position
            long glfwHandle = client.getWindow().getHandle();
            int x = client.getWindow().getX();
            int y = client.getWindow().getY();
            GL2MetalTranslator.getInstance().syncWithGLFWWindow(glfwHandle, x, y, width, height);;

            MetalLogger.info("[GL2MetalInitMixin] Metal window synced to GLFW at ({}, {}) {}x{}", x, y, width, height);
        } else {
            MetalLogger.error("[GL2MetalInitMixin] Failed to initialize GL2Metal");
        }
    }

    /**
     * Sync Metal window with GLFW window on each frame tick and present rendered
     * content.
     */
    @Inject(method = "render", at = @At("TAIL"))
    private void metalrender$onFrameEnd(boolean tick, CallbackInfo ci) {
        if (!GL2MetalManager.isEnabled())
            return;

        MinecraftClient client = (MinecraftClient) (Object) this;
        long glfwHandle = client.getWindow().getHandle();
        int width = client.getWindow().getFramebufferWidth();
        int height = client.getWindow().getFramebufferHeight();
        int x = client.getWindow().getX();
        int y = client.getWindow().getY();

        // Check GLFW minimize state
        boolean glfwMinimized = GLFW.glfwGetWindowAttrib(glfwHandle, GLFW.GLFW_ICONIFIED) == GLFW.GLFW_TRUE;

        // Sync minimize state with Metal window
        if (glfwMinimized != lastMinimized) {
            lastMinimized = glfwMinimized;
            GL2MetalTranslator.getInstance().setMinimized(glfwMinimized);
            MetalLogger.info("[GL2MetalInitMixin] Metal window {} (synced with GLFW)",
                    glfwMinimized ? "minimized" : "restored");
        }

        // Don't sync position/size when minimized
        if (glfwMinimized) {
            return;
        }

        // Sync position and size
        boolean sizeChanged = width != lastWidth || height != lastHeight;
        lastWidth = width;
        lastHeight = height;

        GL2MetalTranslator.getInstance().syncWithGLFWWindow(glfwHandle, x, y, width, height);

        if (sizeChanged) {
            GL2MetalManager.updateWindowSize(width, height);
            MetalLogger.info("[GL2MetalInitMixin] Synced Metal window: {}x{} at ({}, {})",
                    width, height, x, y);
        }

        // Present the Metal frame
        GL2MetalManager.onFrameEnd();

        // Poll Metal window events
        GL2MetalTranslator.getInstance().pollEvents();

        // Check if Metal window should close
        if (GL2MetalTranslator.getInstance().shouldClose()) {
            MetalLogger.info("[GL2MetalInitMixin] Metal window close requested");
            client.scheduleStop();
        }
    }
}
