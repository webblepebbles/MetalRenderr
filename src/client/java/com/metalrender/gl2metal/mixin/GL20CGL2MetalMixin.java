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

@Pseudo
@Mixin(targets = { "org.lwjgl.opengl.GL20" })
public class GL20CGL2MetalMixin {

    @Inject(method = "glCreateProgram", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metalrender$glCreateProgram(CallbackInfoReturnable<Integer> cir) {
        if (GL2MetalManager.shouldInterceptShaders()) {
            cir.setReturnValue(GL2MetalTranslator.getInstance().glCreateProgram());
        }
    }

    @Inject(method = "glUseProgram", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metalrender$glUseProgram(int program, CallbackInfo ci) {
        if (GL2MetalManager.shouldInterceptShaders()) {
            GL2MetalTranslator.getInstance().glUseProgram(program);
            ci.cancel();
        }
    }

    @Inject(method = "glDeleteProgram", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metalrender$glDeleteProgram(int program, CallbackInfo ci) {
        if (GL2MetalManager.shouldInterceptShaders()) {
            GL2MetalTranslator.getInstance().glDeleteProgram(program);
            ci.cancel();
        }
    }

    @Inject(method = "glLinkProgram", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metalrender$glLinkProgram(int program, CallbackInfo ci) {
        if (GL2MetalManager.shouldInterceptShaders()) {
            GL2MetalTranslator.getInstance().glLinkProgram(program);
            ci.cancel();
        }
    }

    @Inject(method = "glCreateShader", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metalrender$glCreateShader(int type, CallbackInfoReturnable<Integer> cir) {
        if (GL2MetalManager.shouldInterceptShaders()) {
            cir.setReturnValue(GL2MetalTranslator.getInstance().glCreateShader(type));
        }
    }

    @Inject(method = "glDeleteShader", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metalrender$glDeleteShader(int shader, CallbackInfo ci) {
        if (GL2MetalManager.shouldInterceptShaders()) {
            GL2MetalTranslator.getInstance().glDeleteShader(shader);
            ci.cancel();
        }
    }

    @Inject(method = "glAttachShader", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metalrender$glAttachShader(int program, int shader, CallbackInfo ci) {
        if (GL2MetalManager.shouldInterceptShaders()) {
            GL2MetalTranslator.getInstance().glAttachShader(program, shader);
            ci.cancel();
        }
    }

    @Inject(method = "glCompileShader", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metalrender$glCompileShader(int shader, CallbackInfo ci) {
        if (GL2MetalManager.shouldInterceptShaders()) {
            GL2MetalTranslator.getInstance().glCompileShader(shader);
            ci.cancel();
        }
    }

    @Inject(method = "glUniform1i", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metalrender$glUniform1i(int location, int v0, CallbackInfo ci) {
        if (GL2MetalManager.shouldInterceptShaders()) {
            GL2MetalTranslator.getInstance().glUniform1i(location, v0);
            ci.cancel();
        }
    }

    @Inject(method = "glUniform1f", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metalrender$glUniform1f(int location, float v0, CallbackInfo ci) {
        if (GL2MetalManager.shouldInterceptShaders()) {
            GL2MetalTranslator.getInstance().glUniform1f(location, v0);
            ci.cancel();
        }
    }

    @Inject(method = "glUniform2f", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metalrender$glUniform2f(int location, float v0, float v1, CallbackInfo ci) {
        if (GL2MetalManager.shouldInterceptShaders()) {
            GL2MetalTranslator.getInstance().glUniform2f(location, v0, v1);
            ci.cancel();
        }
    }

    @Inject(method = "glUniform3f", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metalrender$glUniform3f(int location, float v0, float v1, float v2, CallbackInfo ci) {
        if (GL2MetalManager.shouldInterceptShaders()) {
            GL2MetalTranslator.getInstance().glUniform3f(location, v0, v1, v2);
            ci.cancel();
        }
    }

    @Inject(method = "glUniform4f", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metalrender$glUniform4f(int location, float v0, float v1, float v2, float v3, CallbackInfo ci) {
        if (GL2MetalManager.shouldInterceptShaders()) {
            GL2MetalTranslator.getInstance().glUniform4f(location, v0, v1, v2, v3);
            ci.cancel();
        }
    }

    @Inject(method = "glBlendFuncSeparate", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metalrender$glBlendFuncSeparate(int srcRGB, int dstRGB, int srcAlpha, int dstAlpha,
            CallbackInfo ci) {
        if (GL2MetalManager.shouldInterceptState()) {
            GL2MetalTranslator.getInstance().glBlendFuncSeparate(srcRGB, dstRGB, srcAlpha, dstAlpha);
            ci.cancel();
        }
    }
}
