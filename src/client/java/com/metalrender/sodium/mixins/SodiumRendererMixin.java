package com.metalrender.sodium.mixins;

import com.metalrender.MetalRenderClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(
   targets = {"net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer"}
)
public class SodiumRendererMixin {
   @Inject(
      method = {"drawChunkLayer"},
      at = {@At("HEAD")},
      require = 0
   )
   private void metalrender$replaceRender(@Coerce Object viewport, @Coerce Object matrices, double x, double y, double z, CallbackInfo ci) {
      if (MetalRenderClient.isEnabled() && MetalRenderClient.getWorldRenderer() != null) {
         MetalRenderClient.getWorldRenderer().renderFrame(viewport, matrices, x, y, z);
      }

   }
}
