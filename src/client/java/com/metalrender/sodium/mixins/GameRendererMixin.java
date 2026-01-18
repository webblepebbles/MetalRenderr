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
            // TEST 74: DON'T skip clear, but blit AFTER clear
            // This should put terrain in framebuffer BEFORE sky/entities/GUI render
            clearCount++;

            // Let clear happen normally (don't cancel)
            // The clear will happen, then we can blit
        }
    }

    @Inject(method = "clear", at = @At("RETURN"), remap = false)
    private static void metalrender$blitAfterClear(int mask, boolean getError, CallbackInfo ci) {
        // TEST 75: Don't blit after clear - Metal hasn't rendered yet!
        // Blit happens at flipFrame instead
    }
}
