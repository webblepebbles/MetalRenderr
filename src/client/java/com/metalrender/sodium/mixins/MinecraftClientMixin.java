package com.metalrender.sodium.mixins;

import com.metalrender.MetalRenderClient;
import com.metalrender.performance.PerformanceController;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({MinecraftClient.class})
public class MinecraftClientMixin {
  @Inject(method = {"render"}, at = { @At("HEAD") })
  private void metalrender$startFrame(boolean tick, CallbackInfo ci) {
    if (MetalRenderClient.isEnabled()) {
      PerformanceController.startFrame();
    }
  }

  @Inject(method = {"render"}, at = { @At("TAIL") })
  private void metalrender$endFrame(boolean tick, CallbackInfo ci) {
    if (MetalRenderClient.isEnabled()) {
      PerformanceController.endFrame();
    }
  }
}
