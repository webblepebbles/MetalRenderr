package com.metalrender.sodium.mixins;

import com.metalrender.MetalRenderClient;
import com.metalrender.sodium.backend.MeshShaderBackend;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(
    targets = {"net.caffeinemc.mods.sodium.client.render.chunk.RenderSection"})
public abstract class RenderSectionMixin {
  @Shadow(remap = false) public abstract int getOriginX();

  @Shadow(remap = false) public abstract int getOriginY();

  @Shadow(remap = false) public abstract int getOriginZ();

  @Inject(method = "delete", at = @At("HEAD"), require = 0)
  private void metalrender$onDelete(CallbackInfo ci) {
    MeshShaderBackend backend = MetalRenderClient.getMeshBackend();
    if (backend != null) {
      backend.removeChunkMesh(new BlockPos(this.getOriginX(), this.getOriginY(),
                                           this.getOriginZ()));
    }
  }
}