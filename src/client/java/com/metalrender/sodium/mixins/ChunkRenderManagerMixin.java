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
   targets = {"me.jellysquid.mods.sodium.client.render.chunk.ChunkRenderManager"}
)
public class ChunkRenderManagerMixin {
   @Inject(
      method = {"uploadChunkBuildResult(Lme/jellysquid/mods/sodium/client/render/chunk/compile/ChunkBuildOutput;)V", "upload(Lme/jellysquid/mods/sodium/client/render/chunk/compile/ChunkBuildOutput;)V"},
      at = {@At("HEAD")},
      require = 0
   )
   private void metalrender$onUpload(@Coerce Object result, CallbackInfo ci) {
      if (MetalRenderClient.isEnabled() && MetalRenderClient.getWorldRenderer() != null) {
         MetalRenderClient.getWorldRenderer().uploadBuildResult(result);
      }

   }
}
