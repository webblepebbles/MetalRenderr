package com.metalrender.sodium.mixins;

import com.metalrender.MetalRenderClient;
import com.mojang.blaze3d.systems.RenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RenderSystem.class)
public class GameRendererMixin {
    
    @Inject(method = "clear", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metalrender$skipClear(int mask, boolean getError, CallbackInfo ci) {
        // When MetalRender is active, skip the OpenGL clear so our Metal content shows through
        if (MetalRenderClient.isEnabled() && MetalRenderClient.getWorldRenderer() != null) {
            // Cancel the clear operation - this allows our Metal layer to show through
            ci.cancel();
        }
    }
}
