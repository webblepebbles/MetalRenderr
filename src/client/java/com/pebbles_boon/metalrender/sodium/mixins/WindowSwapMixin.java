package com.pebbles_boon.metalrender.sodium.mixins;
import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.render.MetalWorldRenderer;
import com.pebbles_boon.metalrender.render.gui.MetalGuiRenderer;
import com.pebbles_boon.metalrender.util.MetalLogger;
import net.minecraft.client.util.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(Window.class)
public class WindowSwapMixin {
  private static int swapFrameCount = 0;
  @Inject(method = "swapBuffers", at = @At("HEAD"))
  private void metalrender$beforeSwap(CallbackInfo ci) {
    if (!MetalRenderClient.isEnabled()) {
      return;
    }
    MetalWorldRenderer renderer = MetalRenderClient.getWorldRenderer();
    if (renderer != null) {
      swapFrameCount++;
      if (MetalWorldRenderer.shouldBlitAt("swap")) {
        renderer.forceBlitNow();
        MetalGuiRenderer.getInstance().endFrame();
      }
      if (swapFrameCount <= 5 || swapFrameCount % 600 == 0) {
        MetalLogger.info(
            "[WindowSwapMixin] swapBuffers hook (frame=%d, mode=%s)",
            swapFrameCount, MetalWorldRenderer.getBlitTimingMode());
      }
    }
  }
}
