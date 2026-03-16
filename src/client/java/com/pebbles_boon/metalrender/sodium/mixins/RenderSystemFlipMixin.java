package com.pebbles_boon.metalrender.sodium.mixins;

import com.mojang.blaze3d.systems.RenderSystem;
import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.render.MetalWorldRenderer;
import com.pebbles_boon.metalrender.render.gui.MetalGuiRenderer;
import net.minecraft.client.util.Window;
import net.minecraft.client.util.tracy.TracyFrameCapturer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RenderSystem.class)
public class RenderSystemFlipMixin {

  @Inject(method = "flipFrame", at = @At("HEAD"), remap = false)
  private static void metalrender$beforeFlip(Window window,
                                             TracyFrameCapturer tracyCapture,
                                             CallbackInfo ci) {}

  @Inject(method = "flipFrame", at = @At("TAIL"), remap = false)
  private static void metalrender$afterFlip(Window window,
                                            TracyFrameCapturer tracyCapture,
                                            CallbackInfo ci) {

    if (MetalRenderClient.isEnabled() &&
        MetalGuiRenderer.isOverlayModeEnabled()) {
      MetalGuiRenderer.getInstance().endFrame();
    }
  }
}
