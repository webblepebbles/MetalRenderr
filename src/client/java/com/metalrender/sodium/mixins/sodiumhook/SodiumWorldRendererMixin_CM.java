package com.metalrender.sodium.mixins.sodiumhook;

import com.metalrender.MetalRenderClient;
import com.metalrender.render.MetalWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.minecraft.client.gl.GpuSampler;
import net.minecraft.client.render.BlockRenderLayerGroup;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;

@Pseudo
@Mixin(targets = { "net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer" })
public class SodiumWorldRendererMixin_CM {
  private static int frameCount = 0;
  private static boolean terrainRenderedThisFrame = false;

  @Inject(method = { "setupTerrain" }, at = { @At("HEAD") }, require = 0)
  private void metalrender$setupTerrain(
      @Coerce Object worldRenderer, @Coerce Object viewport,
      @Coerce Object fogParams, boolean isSpectator, boolean captureFrustum,
      @Coerce Object matrices, CallbackInfo ci) {
    terrainRenderedThisFrame = false;
  }

  @Inject(method = { "drawChunkLayer" }, at = { @At("HEAD") }, cancellable = true, require = 0)
  private void metalrender$drawChunkLayer(BlockRenderLayerGroup group, ChunkRenderMatrices matrices, double x, double y,
      double z, GpuSampler terrainSampler, CallbackInfo ci) {
    if (MetalRenderClient.isEnabled()) {
      MetalWorldRenderer renderer = MetalRenderClient.getWorldRenderer();
      if (renderer != null) {

        if (group == BlockRenderLayerGroup.OPAQUE) {
          terrainRenderedThisFrame = true;
          frameCount++;
          renderer.renderFrameWithMatrices(matrices.projection(), matrices.modelView(), x, y, z);
        }
        ci.cancel();
      }
    }
  }
}
