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
@Mixin(targets = { "org.lwjgl.opengl.GL11" })
public class GL11CGL2MetalMixin {

    @Inject(method = "glEnable", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metalrender$glEnable(int cap, CallbackInfo ci) {
        if (GL2MetalManager.shouldInterceptState()) {
            GL2MetalTranslator.getInstance().glEnable(cap);
            ci.cancel();
        }
    }

    @Inject(method = "glDisable", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metalrender$glDisable(int cap, CallbackInfo ci) {
        if (GL2MetalManager.shouldInterceptState()) {
            GL2MetalTranslator.getInstance().glDisable(cap);
            ci.cancel();
        }
    }

    @Inject(method = "glBlendFunc", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metalrender$glBlendFunc(int sfactor, int dfactor, CallbackInfo ci) {
        if (GL2MetalManager.shouldInterceptState()) {
            GL2MetalTranslator.getInstance().glBlendFunc(sfactor, dfactor);
            ci.cancel();
        }
    }

    @Inject(method = "glDepthFunc", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metalrender$glDepthFunc(int func, CallbackInfo ci) {
        if (GL2MetalManager.shouldInterceptState()) {
            GL2MetalTranslator.getInstance().glDepthFunc(func);
            ci.cancel();
        }
    }

    @Inject(method = "glDepthMask", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metalrender$glDepthMask(boolean flag, CallbackInfo ci) {
        if (GL2MetalManager.shouldInterceptState()) {
            GL2MetalTranslator.getInstance().glDepthMask(flag);
            ci.cancel();
        }
    }

    @Inject(method = "glCullFace", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metalrender$glCullFace(int mode, CallbackInfo ci) {
        if (GL2MetalManager.shouldInterceptState()) {
            GL2MetalTranslator.getInstance().glCullFace(mode);
            ci.cancel();
        }
    }

    @Inject(method = "glViewport", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metalrender$glViewport(int x, int y, int width, int height, CallbackInfo ci) {
        if (GL2MetalManager.shouldInterceptState()) {
            GL2MetalTranslator.getInstance().glViewport(x, y, width, height);
            ci.cancel();
        }
    }

    @Inject(method = "glClear", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metalrender$glClear(int mask, CallbackInfo ci) {
        if (GL2MetalManager.shouldInterceptState()) {
            GL2MetalTranslator.getInstance().glClear(mask);
            ci.cancel();
        }
    }

    @Inject(method = "glClearColor", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metalrender$glClearColor(float r, float g, float b, float a, CallbackInfo ci) {
        if (GL2MetalManager.shouldInterceptState()) {
            GL2MetalTranslator.getInstance().glClearColor(r, g, b, a);
            ci.cancel();
        }
    }

    @Inject(method = "glDrawArrays", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metalrender$glDrawArrays(int mode, int first, int count, CallbackInfo ci) {
        if (GL2MetalManager.shouldInterceptDrawCalls()) {
            GL2MetalTranslator.getInstance().glDrawArrays(mode, first, count);
            ci.cancel();
        }
    }

    @Inject(method = "glDrawElements(IIIJ)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metalrender$glDrawElements(int mode, int count, int type, long indices, CallbackInfo ci) {
        if (GL2MetalManager.shouldInterceptDrawCalls()) {
            GL2MetalTranslator.getInstance().glDrawElements(mode, count, type, indices);
            ci.cancel();
        }
    }

    @Inject(method = "glGenTextures()I", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metalrender$glGenTextures(CallbackInfoReturnable<Integer> cir) {
        if (GL2MetalManager.shouldInterceptTextures()) {
            cir.setReturnValue(GL2MetalTranslator.getInstance().glGenTextures());
        }
    }

    @Inject(method = "glDeleteTextures(I)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metalrender$glDeleteTextures(int texture, CallbackInfo ci) {
        if (GL2MetalManager.shouldInterceptTextures()) {
            GL2MetalTranslator.getInstance().glDeleteTextures(texture);
            ci.cancel();
        }
    }

    @Inject(method = "glBindTexture", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metalrender$glBindTexture(int target, int texture, CallbackInfo ci) {
        if (GL2MetalManager.shouldInterceptTextures()) {
            GL2MetalTranslator.getInstance().glBindTexture(target, texture);
            ci.cancel();
        }
    }

    @Inject(method = "glColorMask", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metalrender$glColorMask(boolean r, boolean g, boolean b, boolean a, CallbackInfo ci) {
        if (GL2MetalManager.shouldInterceptState()) {
            GL2MetalTranslator.getInstance().glColorMask(r, g, b, a);
            ci.cancel();
        }
    }

    @Inject(method = "glScissor", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metalrender$glScissor(int x, int y, int width, int height, CallbackInfo ci) {
        if (GL2MetalManager.shouldInterceptState()) {
            GL2MetalTranslator.getInstance().glScissor(x, y, width, height);
            ci.cancel();
        }
    }

    @Inject(method = "glPolygonMode", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metalrender$glPolygonMode(int face, int mode, CallbackInfo ci) {
        if (GL2MetalManager.shouldInterceptState()) {
            GL2MetalTranslator.getInstance().glPolygonMode(face, mode);
            ci.cancel();
        }
    }

    @Inject(method = "glPolygonOffset", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metalrender$glPolygonOffset(float factor, float units, CallbackInfo ci) {
        if (GL2MetalManager.shouldInterceptState()) {
            GL2MetalTranslator.getInstance().glPolygonOffset(factor, units);
            ci.cancel();
        }
    }
}
