package com.metalrender.sodium.mixins.lwjgl;

import com.metalrender.backend.GLIntercept;
import com.metalrender.config.MetalRenderConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(
   targets = {"org.lwjgl.opengl.GL11C"}
)
public class GL11CMixin {
   @Inject(
      method = {"glDrawElements"},
      at = {@At("HEAD")},
      remap = false
   )
   private static void metalrender$onDrawElements(int mode, int count, int type, long indicesOffset, CallbackInfo ci) {
      if (MetalRenderConfig.swapOpaque() || MetalRenderConfig.swapCutout() || MetalRenderConfig.swapTranslucent()) {
         GLIntercept.onDrawElements(mode, count, type, indicesOffset, (float[])null);
      }

   }
}
