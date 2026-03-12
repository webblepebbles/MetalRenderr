package com.pebbles_boon.metalrender.sodium.mixins;

import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.render.MetalWorldRenderer;
import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientWorld.class)
public class ClientWorldMixin {

  @Inject(method = "scheduleBlockRerenderIfNeeded", at = @At("HEAD"),
          require = 0)
  private void
  metalrender$onBlockChanged(BlockPos pos, BlockState old, BlockState updated,
                             CallbackInfo ci) {
    metalrender$triggerRebuild(pos);
  }

  @Inject(method = "handleBlockUpdate", at = @At("RETURN"), require = 0)
  private void metalrender$onHandleBlockUpdate(BlockPos pos, BlockState state,
                                               int flags, CallbackInfo ci) {
    metalrender$triggerRebuild(pos);
  }

  private void metalrender$triggerRebuild(BlockPos pos) {

    com.pebbles_boon.metalrender.util.MetalLogger.info(
        "[ClientWorldMixin] Block update at %d,%d,%d", pos.getX(), pos.getY(),
        pos.getZ());

    if (!MetalRenderClient.getConfig().enableMetalRendering)
      return;

    MetalWorldRenderer worldRenderer = MetalWorldRenderer.getInstance();
    if (worldRenderer == null || !worldRenderer.isReady())
      return;

    worldRenderer.scheduleSectionRebuild(pos.getX(), pos.getY(), pos.getZ());
  }
}
