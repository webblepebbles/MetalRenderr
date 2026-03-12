package com.pebbles_boon.metalrender.sodium.mixins;

import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.render.MetalWorldRenderer;
import net.minecraft.client.gl.GpuSampler;
import net.minecraft.client.render.BlockRenderLayerGroup;
import net.minecraft.client.render.SectionRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SectionRenderState.class)
public class WorldRendererTerrainMixin {

  @Inject(method = "renderSection", at = @At("HEAD"), cancellable = true)
  private void metalrender$skipGLTerrain(BlockRenderLayerGroup layerGroup,
                                         GpuSampler sampler, CallbackInfo ci) {
    if (MetalRenderClient.isEnabled()) {
      MetalWorldRenderer wr = MetalRenderClient.getWorldRenderer();
      if (wr != null && wr.shouldRenderWithMetal()) {
        ci.cancel();
      }
    }
  }
}
