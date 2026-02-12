package com.metalrender.sodium.mixins;

import com.metalrender.MetalRenderClient;
import com.metalrender.util.MetalLogger;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.SkyRendering;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SkyRendering.class)
public class SkyRenderingMixin {

    @Inject(method = "renderSky", at = @At("HEAD"), cancellable = true)
    private static void metalrender$beforeRenderSky(
            Frustum frustum, Camera camera, GpuBufferSlice bufferSlice,
            CallbackInfo ci) {
    }

    @Inject(method = "renderSkyDark", at = @At("HEAD"), cancellable = true)
    private static void metalrender$beforeRenderSkyDark(CallbackInfo ci) {
    }
}
