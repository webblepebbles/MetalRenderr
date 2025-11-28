package com.metalrender.sodium.mixins.lwjgl;

import com.metalrender.backend.GLIntercept;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = { "org.lwjgl.opengl.GL31C" })
public class GL31CMixin {
    @Inject(method = { "glCopyBufferSubData" }, at = { @At("HEAD") }, cancellable = true, remap = false)
    private static void metalrender$onCopyBufferSubData(int readTarget,
            int writeTarget,
            long readOffset,
            long writeOffset,
            long size,
            CallbackInfo ci) {
        if (GLIntercept.onCopyBufferSubData(readTarget, writeTarget, readOffset,
                writeOffset, size)) {
            ci.cancel();
        }
    }
}
