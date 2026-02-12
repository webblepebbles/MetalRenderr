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

@Mixin(MinecraftClient.class)
public class GL2MetalInitMixin {

    private static boolean gl2metalInitAttempted = false;
    private static int lastWidth = 0;
    private static int lastHeight = 0;
    private static boolean lastMinimized = false;

    
    @Inject(method = "run", at = @At("HEAD"))
    private void metalrender$initGL2Metal(CallbackInfo ci) {
        if (gl2metalInitAttempted)
            return;
        gl2metalInitAttempted = true;
        boolean enableGL2Metal = Boolean.getBoolean("metalrender.gl2metal");

        if (!enableGL2Metal) {
            MetalLogger.info(
                    "[GL2MetalInitMixin] GL2Metal mode not requested (use -Dmetalrender.gl2metal=true to enable)");
            return;
        }
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
            long glfwHandle = client.getWindow().getHandle();
            int x = client.getWindow().getX();
            int y = client.getWindow().getY();
            GL2MetalTranslator.getInstance().syncWithGLFWWindow(glfwHandle, x, y, width, height);;

            MetalLogger.info("[GL2MetalInitMixin] Metal window synced to GLFW at ({}, {}) {}x{}", x, y, width, height);
        } else {
            MetalLogger.error("[GL2MetalInitMixin] Failed to initialize GL2Metal");
        }
    }

    
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
        boolean glfwMinimized = GLFW.glfwGetWindowAttrib(glfwHandle, GLFW.GLFW_ICONIFIED) == GLFW.GLFW_TRUE;
        if (glfwMinimized != lastMinimized) {
            lastMinimized = glfwMinimized;
            GL2MetalTranslator.getInstance().setMinimized(glfwMinimized);
            MetalLogger.info("[GL2MetalInitMixin] Metal window {} (synced with GLFW)",
                    glfwMinimized ? "minimized" : "restored");
        }
        if (glfwMinimized) {
            return;
        }
        boolean sizeChanged = width != lastWidth || height != lastHeight;
        lastWidth = width;
        lastHeight = height;

        GL2MetalTranslator.getInstance().syncWithGLFWWindow(glfwHandle, x, y, width, height);

        if (sizeChanged) {
            GL2MetalManager.updateWindowSize(width, height);
            MetalLogger.info("[GL2MetalInitMixin] Synced Metal window: {}x{} at ({}, {})",
                    width, height, x, y);
        }
        GL2MetalManager.onFrameEnd();
        GL2MetalTranslator.getInstance().pollEvents();
        if (GL2MetalTranslator.getInstance().shouldClose()) {
            MetalLogger.info("[GL2MetalInitMixin] Metal window close requested");
            client.scheduleStop();
        }
    }
}
