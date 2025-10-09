package com.metalrender.sodium.mixins.lwjgl;

import com.metalrender.backend.GLIntercept;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "org.lwjgl.opengl.GL11C")
public class GL11CMixin {
    @Inject(method = "glDrawElements", at = @At("HEAD"), remap = false)
    private static void metalrender$onDrawElements(int mode, int count, int type, long indicesOffset, CallbackInfo ci) {
        // Fast path: avoid method call overhead when draw swap is disabled (hottest path!)
        if (com.metalrender.config.MetalRenderConfig.swapOpaque()
            || com.metalrender.config.MetalRenderConfig.swapCutout()
            || com.metalrender.config.MetalRenderConfig.swapTranslucent()) {
            // We don't have a view-projection here; provide null. The backend will ignore begin/end if so.
            GLIntercept.onDrawElements(mode, count, type, indicesOffset, null);
        }
    }
}
