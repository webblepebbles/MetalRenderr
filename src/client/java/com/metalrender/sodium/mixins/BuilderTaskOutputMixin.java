package com.metalrender.sodium.mixins;

import com.metalrender.MetalRenderClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks into Sodium 0.7.x's ChunkBuildOutput when it's created
 */
@Pseudo
@Mixin(targets = {"net.caffeinemc.mods.sodium.client.render.chunk.compile." +
                  "ChunkBuildOutput"})
public class BuilderTaskOutputMixin {

  @Inject(method = "<init>", at = @At("RETURN"), require = 0)
  private void metalrender$onChunkBuildOutputCreated(CallbackInfo ci) {
    if (MetalRenderClient.isEnabled() &&
        MetalRenderClient.getWorldRenderer() != null) {
      try {
        // 'this' is the ChunkBuildOutput instance
        MetalRenderClient.getWorldRenderer().uploadBuildResult(this);
      } catch (Exception e) {
        System.err.println("[MetalRender ERROR] Failed to upload chunk: " +
                           e.getMessage());
        e.printStackTrace();
      }
    }
  }
}
