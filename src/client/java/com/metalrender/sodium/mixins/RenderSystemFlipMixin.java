package com.metalrender.sodium.mixins;

import com.metalrender.MetalRenderClient;
import com.metalrender.gl2metal.GL2MetalManager;
import com.metalrender.nativebridge.NativeBridge;
import com.metalrender.render.MetalWorldRenderer;
import com.metalrender.render.MetalSurfaceManager;
import com.metalrender.util.MetalLogger;
import net.minecraft.client.util.Window;
import net.minecraft.client.util.tracy.TracyFrameCapturer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import org.lwjgl.BufferUtils;

@Mixin(RenderSystem.class)
public class RenderSystemFlipMixin {

    private static int frameCount = 0;
    private static boolean fullMetalMode = false; // DISABLED - CAMetalLayer freezes. Using IOSurface blit.

    @Inject(method = "flipFrame", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metalrender$beforeFlip(Window window, TracyFrameCapturer tracyCapture, CallbackInfo ci) {
        // Skip if GL2Metal mode is active (it handles its own presentation)
        if (GL2MetalManager.isEnabled()) {
            return;
        }

        if (MetalRenderClient.isEnabled()) {
            // Only present Metal if we are in-game (world is loaded)
            // This leaves Main Menu, Loading Screen, etc. to Vanilla OpenGL
            net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
            if (client.world == null) {
                return;
            }

            MetalWorldRenderer renderer = MetalRenderClient.getWorldRenderer();
            if (renderer != null) {
                frameCount++;

                // Full Metal mode: Present directly to CAMetalLayer
                if (fullMetalMode) {
                    long handle = renderer.getHandle();
                    if (handle != 0) {
                        // Ensure CAMetalLayer is attached (only succeeds after game loaded)
                        MetalSurfaceManager.ensureSurface(handle);

                        // Wait for Metal rendering to complete
                        NativeBridge.nWaitForRender(handle);

                        // Present the Metal content to CAMetalLayer
                        boolean presented = NativeBridge.nPresentFrame(handle);

                        renderer.resetFrameState();

                        // CANCEL OpenGL swap - we're presenting via Metal!
                        if (presented) {
                            ci.cancel();
                            return;
                        }
                    }
                }

                // IOSurface blit mode: ENABLED - blit at end of frame after GUI rendered
                // Now that Metal renders GUI too, we blit AFTER everything is done
                if (true) { // ENABLED - Metal renders everything, blit at end
                    long handle = renderer.getHandle();
                    if (handle != 0) {
                        NativeBridge.nWaitForRender(handle);

                        // Blit to whatever FBO is currently bound
                        int currentFbo = GL30.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);

                        if (frameCount <= 5 || frameCount % 60 == 0) {
                            MetalLogger.info("[RenderSystemFlipMixin] Current FBO before blit: %d", currentFbo);
                        }

                        renderer.forceBlitNow();

                        if (frameCount <= 5 || frameCount % 60 == 0) {
                            MetalLogger.info("[RenderSystemFlipMixin] Blit at flipFrame, frame %d",
                                    frameCount);
                        }
                    }
                }
                renderer.resetFrameState();
            }
        }
    }

    @Inject(method = "flipFrame", at = @At("TAIL"), remap = false)
    private static void metalrender$afterFlip(Window window, TracyFrameCapturer tracyCapture, CallbackInfo ci) {
        // No-op for performance
    }
}
