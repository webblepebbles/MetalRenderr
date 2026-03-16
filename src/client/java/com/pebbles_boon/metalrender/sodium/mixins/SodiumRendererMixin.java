package com.pebbles_boon.metalrender.sodium.mixins;

import com.pebbles_boon.metalrender.MetalRenderClient;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.minecraft.client.gl.GpuSampler;
import net.minecraft.client.render.BlockRenderLayerGroup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer")
public class SodiumRendererMixin {
  @Inject(method = "drawChunkLayer", at = @At("HEAD"), require = 0)
  private void metalrender$replaceRender(BlockRenderLayerGroup layerGroup,
                                         ChunkRenderMatrices matrices, double x,
                                         double y, double z, GpuSampler sampler,
                                         CallbackInfo ci) {
    if (MetalRenderClient.isEnabled() &&
        MetalRenderClient.getWorldRenderer() != null) {
      MetalRenderClient.getWorldRenderer().renderFrame(layerGroup, matrices, x,
                                                       y, z);
    }
  }
}
