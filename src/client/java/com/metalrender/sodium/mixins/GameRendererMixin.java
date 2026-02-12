package com.metalrender.sodium.mixins;

import com.metalrender.MetalRenderClient;
import com.metalrender.render.MetalWorldRenderer;
import com.mojang.blaze3d.systems.RenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RenderSystem.class)
public class GameRendererMixin {

    private static int clearCount = 0;

    @Inject(method = "clear", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metalrender$skipClear(int mask, boolean getError, CallbackInfo ci) {

        if (MetalRenderClient.isEnabled() && MetalRenderClient.getWorldRenderer() != null) {
            clearCount++;
        }
    }

    @Inject(method = "clear", at = @At("RETURN"), remap = false)
    private static void metalrender$blitAfterClear(int mask, boolean getError, CallbackInfo ci) {
    }
}
