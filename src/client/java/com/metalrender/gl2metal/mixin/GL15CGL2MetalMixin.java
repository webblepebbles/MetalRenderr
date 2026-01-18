package com.metalrender.gl2metal.mixin;

import com.metalrender.gl2metal.GL2MetalManager;
import com.metalrender.gl2metal.GL2MetalTranslator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.ByteBuffer;

/**
 * GL15 interception for GL2Metal mode.
 * Intercepts buffer object calls and routes them to Metal.
 * 
 * NOTE: We target GL15 (wrapper class) instead of GL15C because GL15C methods are 
 * native and cannot be injected into. GL15 wraps GL15C with regular Java methods.
 */
@Pseudo
@Mixin(targets = { "org.lwjgl.opengl.GL15" })
public class GL15CGL2MetalMixin {

    // ========================================================================
    // Buffer Generation/Deletion
    // ========================================================================

    @Inject(method = "glGenBuffers()I", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metalrender$glGenBuffers(CallbackInfoReturnable<Integer> cir) {
        if (GL2MetalManager.shouldInterceptBuffers()) {
            cir.setReturnValue(GL2MetalTranslator.getInstance().glGenBuffers());
        }
    }

    @Inject(method = "glDeleteBuffers(I)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metalrender$glDeleteBuffers(int buffer, CallbackInfo ci) {
        if (GL2MetalManager.shouldInterceptBuffers()) {
            GL2MetalTranslator.getInstance().glDeleteBuffers(buffer);
            ci.cancel();
        }
    }

    // ========================================================================
    // Buffer Binding
    // ========================================================================

    @Inject(method = "glBindBuffer", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metalrender$glBindBuffer(int target, int buffer, CallbackInfo ci) {
        if (GL2MetalManager.shouldInterceptBuffers()) {
            GL2MetalTranslator.getInstance().glBindBuffer(target, buffer);
            ci.cancel();
        }
    }

    // ========================================================================
    // Buffer Data
    // ========================================================================

    @Inject(method = "glBufferData(IJI)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metalrender$glBufferDataSize(int target, long size, int usage, CallbackInfo ci) {
        if (GL2MetalManager.shouldInterceptBuffers()) {
            GL2MetalTranslator.getInstance().glBufferData(target, size, usage);
            ci.cancel();
        }
    }

    @Inject(method = "glBufferData(ILjava/nio/ByteBuffer;I)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metalrender$glBufferDataBytes(int target, ByteBuffer data, int usage, CallbackInfo ci) {
        if (GL2MetalManager.shouldInterceptBuffers()) {
            GL2MetalTranslator.getInstance().glBufferData(target, data, usage);
            ci.cancel();
        }
    }

    @Inject(method = "nglBufferData", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metalrender$nglBufferData(int target, long size, long data, int usage, CallbackInfo ci) {
        if (GL2MetalManager.shouldInterceptBuffers()) {
            GL2MetalTranslator.getInstance().nglBufferData(target, size, data, usage);
            ci.cancel();
        }
    }

    // ========================================================================
    // Buffer Sub Data
    // ========================================================================

    @Inject(method = "glBufferSubData(IJLjava/nio/ByteBuffer;)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metalrender$glBufferSubData(int target, long offset, ByteBuffer data, CallbackInfo ci) {
        if (GL2MetalManager.shouldInterceptBuffers()) {
            GL2MetalTranslator.getInstance().glBufferSubData(target, offset, data);
            ci.cancel();
        }
    }

    @Inject(method = "nglBufferSubData", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metalrender$nglBufferSubData(int target, long offset, long size, long data, CallbackInfo ci) {
        if (GL2MetalManager.shouldInterceptBuffers()) {
            GL2MetalTranslator.getInstance().nglBufferSubData(target, offset, size, data);
            ci.cancel();
        }
    }
}
