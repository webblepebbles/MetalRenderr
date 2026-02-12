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
    private static boolean fullMetalMode = false; 

    @Inject(method = "flipFrame", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metalrender$beforeFlip(Window window, TracyFrameCapturer tracyCapture, CallbackInfo ci) {
        if (GL2MetalManager.isEnabled()) {
            return;
        }

        if (MetalRenderClient.isEnabled()) {
            net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
            if (client.world == null) {
                return;
            }

            MetalWorldRenderer renderer = MetalRenderClient.getWorldRenderer();
            if (renderer != null) {
                frameCount++;
                renderer.resetFrameState();
            }
        }
    }

    @Inject(method = "flipFrame", at = @At("TAIL"), remap = false)
    private static void metalrender$afterFlip(Window window, TracyFrameCapturer tracyCapture, CallbackInfo ci) {
    }
}
