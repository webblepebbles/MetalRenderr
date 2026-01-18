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
 * GL20C interception for GL2Metal mode.
 * Intercepts shader and program calls and routes them to Metal.
 */
@Pseudo
@Mixin(targets = { "org.lwjgl.opengl.GL20C" })
public class GL20CGL2MetalMixin {

    // ========================================================================
    // Program Functions
    // ========================================================================

    @Inject(method = "glCreateProgram", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metalrender$glCreateProgram(CallbackInfoReturnable<Integer> cir) {
        if (GL2MetalManager.isEnabled()) {
            cir.setReturnValue(GL2MetalTranslator.getInstance().glCreateProgram());
        }
    }

    @Inject(method = "glUseProgram", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metalrender$glUseProgram(int program, CallbackInfo ci) {
        if (GL2MetalManager.isEnabled()) {
            GL2MetalTranslator.getInstance().glUseProgram(program);
            ci.cancel();
        }
    }

    @Inject(method = "glDeleteProgram", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metalrender$glDeleteProgram(int program, CallbackInfo ci) {
        if (GL2MetalManager.isEnabled()) {
            GL2MetalTranslator.getInstance().glDeleteProgram(program);
            ci.cancel();
        }
    }

    @Inject(method = "glLinkProgram", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metalrender$glLinkProgram(int program, CallbackInfo ci) {
        if (GL2MetalManager.isEnabled()) {
            GL2MetalTranslator.getInstance().glLinkProgram(program);
            ci.cancel();
        }
    }

    // ========================================================================
    // Shader Functions
    // ========================================================================

    @Inject(method = "glCreateShader", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metalrender$glCreateShader(int type, CallbackInfoReturnable<Integer> cir) {
        if (GL2MetalManager.isEnabled()) {
            cir.setReturnValue(GL2MetalTranslator.getInstance().glCreateShader(type));
        }
    }

    @Inject(method = "glDeleteShader", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metalrender$glDeleteShader(int shader, CallbackInfo ci) {
        if (GL2MetalManager.isEnabled()) {
            GL2MetalTranslator.getInstance().glDeleteShader(shader);
            ci.cancel();
        }
    }

    @Inject(method = "glAttachShader", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metalrender$glAttachShader(int program, int shader, CallbackInfo ci) {
        if (GL2MetalManager.isEnabled()) {
            GL2MetalTranslator.getInstance().glAttachShader(program, shader);
            ci.cancel();
        }
    }

    @Inject(method = "glCompileShader", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metalrender$glCompileShader(int shader, CallbackInfo ci) {
        if (GL2MetalManager.isEnabled()) {
            GL2MetalTranslator.getInstance().glCompileShader(shader);
            ci.cancel();
        }
    }

    // ========================================================================
    // Uniform Functions
    // ========================================================================

    @Inject(method = "glGetUniformLocation", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metalrender$glGetUniformLocation(int program, CharSequence name,
            CallbackInfoReturnable<Integer> cir) {
        if (GL2MetalManager.isEnabled()) {
            cir.setReturnValue(GL2MetalTranslator.getInstance().glGetUniformLocation(program, name.toString()));
        }
    }

    @Inject(method = "glUniform1i", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metalrender$glUniform1i(int location, int v0, CallbackInfo ci) {
        if (GL2MetalManager.isEnabled()) {
            GL2MetalTranslator.getInstance().glUniform1i(location, v0);
            ci.cancel();
        }
    }

    @Inject(method = "glUniform1f", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metalrender$glUniform1f(int location, float v0, CallbackInfo ci) {
        if (GL2MetalManager.isEnabled()) {
            GL2MetalTranslator.getInstance().glUniform1f(location, v0);
            ci.cancel();
        }
    }

    @Inject(method = "glUniform2f", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metalrender$glUniform2f(int location, float v0, float v1, CallbackInfo ci) {
        if (GL2MetalManager.isEnabled()) {
            GL2MetalTranslator.getInstance().glUniform2f(location, v0, v1);
            ci.cancel();
        }
    }

    @Inject(method = "glUniform3f", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metalrender$glUniform3f(int location, float v0, float v1, float v2, CallbackInfo ci) {
        if (GL2MetalManager.isEnabled()) {
            GL2MetalTranslator.getInstance().glUniform3f(location, v0, v1, v2);
            ci.cancel();
        }
    }

    @Inject(method = "glUniform4f", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metalrender$glUniform4f(int location, float v0, float v1, float v2, float v3, CallbackInfo ci) {
        if (GL2MetalManager.isEnabled()) {
            GL2MetalTranslator.getInstance().glUniform4f(location, v0, v1, v2, v3);
            ci.cancel();
        }
    }

    // ========================================================================
    // Vertex Attribute Functions
    // ========================================================================

    @Inject(method = "glVertexAttribPointer", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metalrender$glVertexAttribPointer(int index, int size, int type, boolean normalized, int stride,
            long pointer, CallbackInfo ci) {
        if (GL2MetalManager.isEnabled()) {
            GL2MetalTranslator.getInstance().glVertexAttribPointer(index, size, type, normalized, stride, pointer);
            ci.cancel();
        }
    }

    @Inject(method = "glEnableVertexAttribArray", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metalrender$glEnableVertexAttribArray(int index, CallbackInfo ci) {
        if (GL2MetalManager.isEnabled()) {
            GL2MetalTranslator.getInstance().glEnableVertexAttribArray(index);
            ci.cancel();
        }
    }

    @Inject(method = "glDisableVertexAttribArray", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metalrender$glDisableVertexAttribArray(int index, CallbackInfo ci) {
        if (GL2MetalManager.isEnabled()) {
            GL2MetalTranslator.getInstance().glDisableVertexAttribArray(index);
            ci.cancel();
        }
    }

    // ========================================================================
    // Blend Function Separate
    // ========================================================================

    @Inject(method = "glBlendFuncSeparate", at = @At("HEAD"), cancellable = true, remap = false)
    private static void metalrender$glBlendFuncSeparate(int srcRGB, int dstRGB, int srcAlpha, int dstAlpha,
            CallbackInfo ci) {
        if (GL2MetalManager.isEnabled()) {
            GL2MetalTranslator.getInstance().glBlendFuncSeparate(srcRGB, dstRGB, srcAlpha, dstAlpha);
            ci.cancel();
        }
    }
}
