package com.metalrender.sodium.mixins.sodiumhook;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(
    targets = {"net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer"})
public class SodiumWorldRendererMixin_CM {
  @Inject(method = {"setupTerrain"}, at = { @At("HEAD") }, require = 0)
  private void metalrender$setupTerrain(
      @Coerce Object worldRenderer, @Coerce Object viewport,
      @Coerce Object fogParams, boolean isSpectator, boolean captureFrustum,
      @Coerce Object matrices, CallbackInfo ci) {}

  @Inject(method = {"drawChunkLayer"}, at = { @At("HEAD") }, cancellable = true,
          require = 0)
  private void metalrender$drawChunkLayer(@Coerce Object terrainPass,
                                          @Coerce Object matrices, double x,
                                          double y, double z, CallbackInfo ci) {
  }
}
