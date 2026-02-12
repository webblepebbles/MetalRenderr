package com.metalrender.gl2metal.mixin;

import com.metalrender.gl2metal.GL2MetalManager;
import com.metalrender.gl2metal.GL2MetalTranslator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = { "org.lwjgl.opengl.GL30" })
public class GL30CGL2MetalMixin {

    @Inject(method = "glGenVertexArrays()I", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metalrender$glGenVertexArrays(CallbackInfoReturnable<Integer> cir) {
        if (GL2MetalManager.shouldInterceptVAOs()) {
            cir.setReturnValue(GL2MetalTranslator.getInstance().glGenVertexArrays());
        }
    }

    @Inject(method = "glDeleteVertexArrays(I)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metalrender$glDeleteVertexArrays(int array, CallbackInfo ci) {
        if (GL2MetalManager.shouldInterceptVAOs()) {
            GL2MetalTranslator.getInstance().glDeleteVertexArrays(array);
            ci.cancel();
        }
    }

    @Inject(method = "glBindVertexArray", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metalrender$glBindVertexArray(int array, CallbackInfo ci) {
        if (GL2MetalManager.shouldInterceptVAOs()) {
            GL2MetalTranslator.getInstance().glBindVertexArray(array);
            ci.cancel();
        }
    }

    @Inject(method = "glGenFramebuffers()I", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metalrender$glGenFramebuffers(CallbackInfoReturnable<Integer> cir) {
        if (GL2MetalManager.shouldInterceptFBOs()) {
            cir.setReturnValue(GL2MetalTranslator.getInstance().glGenFramebuffers());
        }
    }

    @Inject(method = "glDeleteFramebuffers(I)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metalrender$glDeleteFramebuffers(int framebuffer, CallbackInfo ci) {
        if (GL2MetalManager.shouldInterceptFBOs()) {
            GL2MetalTranslator.getInstance().glDeleteFramebuffers(framebuffer);
            ci.cancel();
        }
    }

    @Inject(method = "glBindFramebuffer", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metalrender$glBindFramebuffer(int target, int framebuffer, CallbackInfo ci) {
        if (GL2MetalManager.shouldInterceptFBOs()) {
            GL2MetalTranslator.getInstance().glBindFramebuffer(target, framebuffer);
            ci.cancel();
        }
    }

    @Inject(method = "glFramebufferTexture2D", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metalrender$glFramebufferTexture2D(int target, int attachment, int textarget, int texture,
            int level, CallbackInfo ci) {
        if (GL2MetalManager.shouldInterceptFBOs()) {
            GL2MetalTranslator.getInstance().glFramebufferTexture2D(target, attachment, textarget, texture, level);
            ci.cancel();
        }
    }

    @Inject(method = "glCheckFramebufferStatus", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metalrender$glCheckFramebufferStatus(int target, CallbackInfoReturnable<Integer> cir) {
        if (GL2MetalManager.shouldInterceptFBOs()) {
            cir.setReturnValue(GL2MetalTranslator.getInstance().glCheckFramebufferStatus(target));
        }
    }

    @Inject(method = "glGenRenderbuffers()I", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metalrender$glGenRenderbuffers(CallbackInfoReturnable<Integer> cir) {
        if (GL2MetalManager.shouldInterceptFBOs()) {
            cir.setReturnValue(GL2MetalTranslator.getInstance().glGenRenderbuffers());
        }
    }

    @Inject(method = "glDeleteRenderbuffers(I)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metalrender$glDeleteRenderbuffers(int renderbuffer, CallbackInfo ci) {
        if (GL2MetalManager.shouldInterceptFBOs()) {
            GL2MetalTranslator.getInstance().glDeleteRenderbuffers(renderbuffer);
            ci.cancel();
        }
    }

    @Inject(method = "glBindRenderbuffer", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metalrender$glBindRenderbuffer(int target, int renderbuffer, CallbackInfo ci) {
        if (GL2MetalManager.shouldInterceptFBOs()) {
            GL2MetalTranslator.getInstance().glBindRenderbuffer(target, renderbuffer);
            ci.cancel();
        }
    }

    @Inject(method = "glRenderbufferStorage", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metalrender$glRenderbufferStorage(int target, int internalformat, int width, int height,
            CallbackInfo ci) {
        if (GL2MetalManager.shouldInterceptFBOs()) {
            GL2MetalTranslator.getInstance().glRenderbufferStorage(target, internalformat, width, height);
            ci.cancel();
        }
    }

    @Inject(method = "glFramebufferRenderbuffer", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metalrender$glFramebufferRenderbuffer(int target, int attachment, int renderbuffertarget,
            int renderbuffer, CallbackInfo ci) {
        if (GL2MetalManager.shouldInterceptFBOs()) {
            GL2MetalTranslator.getInstance().glFramebufferRenderbuffer(target, attachment, renderbuffertarget,
                    renderbuffer);
            ci.cancel();
        }
    }
}
