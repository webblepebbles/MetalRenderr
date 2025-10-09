package com.metalrender.sodium.mixins;

import com.metalrender.MetalRenderClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer")
public class SodiumRendererMixin {
    @Inject(method = {"render(Lme/jellysquid/mods/sodium/client/render/viewport/Viewport;Lme/jellysquid/mods/sodium/"
                      + "client/render/chunk/ChunkRenderMatrices;DDD)V",
                "renderFrame(Lme/jellysquid/mods/sodium/client/render/viewport/Viewport;Lme/jellysquid/mods/sodium/"
                + "client/render/chunk/ChunkRenderMatrices;DDD)V"},
        at = @At("HEAD"), cancellable = true, require = 0)
    private void metalrender$replaceRender(
        @Coerce Object viewport, @Coerce Object matrices, double x, double y, double z, CallbackInfo ci) {
        if (MetalRenderClient.isEnabled() && MetalRenderClient.getWorldRenderer() != null) {
            MetalRenderClient.getWorldRenderer().renderFrame(viewport, matrices, x, y, z);
            ci.cancel();
        }
    }
}
