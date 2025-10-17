package com.metalrender.sodium.mixins;

import com.metalrender.MetalRenderClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(
   targets = {"me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer"}
)
public class SodiumWorldRendererMixin {
   @Inject(
      method = {"tick"},
      at = {@At("HEAD")},
      cancellable = true,
      require = 0
   )
   private void metalrender$tick(CallbackInfo ci) {
      if (MetalRenderClient.isEnabled()) {
      }

   }
}
