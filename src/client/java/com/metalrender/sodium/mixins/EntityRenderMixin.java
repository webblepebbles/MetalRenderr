package com.metalrender.sodium.mixins;

import com.metalrender.MetalRenderClient;
import com.metalrender.entity.EntityCaptureState;
import com.metalrender.entity.MetalEntityRenderer;
import com.metalrender.util.MetalLogger;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.ObjectAllocator;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WorldRenderer.class)
public abstract class EntityRenderMixin {

    private static int frameCount = 0;

    
    @Inject(method = "render", at = @At("HEAD"), require = 0)
    private void metalrender$onRenderStart(
            ObjectAllocator allocator, RenderTickCounter tickCounter,
            boolean renderBlockOutline,
            Camera camera, Matrix4f positionMatrix, Matrix4f projectionMatrix, Matrix4f matrix4f3,
            GpuBufferSlice bufferSlice, Vector4f vector4f, boolean bl,
            CallbackInfo ci) {
        frameCount++;

        if (MetalRenderClient.isEnabled()) {
            MetalEntityRenderer entityRenderer = MetalEntityRenderer.getInstance();
            if (entityRenderer.isEnabled()) {
                Matrix4f viewProj = new Matrix4f();
                projectionMatrix.mul(positionMatrix, viewProj);
                entityRenderer.beginFrame(camera, viewProj);
                EntityCaptureState.enable();
            }
        }
    }

    
    @Inject(method = "render", at = @At("RETURN"), require = 0)
    private void metalrender$onRenderEnd(
            ObjectAllocator allocator, RenderTickCounter tickCounter,
            boolean renderBlockOutline,
            Camera camera, Matrix4f positionMatrix, Matrix4f projectionMatrix, Matrix4f matrix4f3,
            GpuBufferSlice bufferSlice, Vector4f vector4f, boolean bl,
            CallbackInfo ci) {
        if (MetalRenderClient.isEnabled()) {
            MetalEntityRenderer entityRenderer = MetalEntityRenderer.getInstance();
            if (entityRenderer.isEnabled()) {
                EntityCaptureState.disable();
                entityRenderer.endCapture();
                entityRenderer.renderEntities();
                if (frameCount % 300 == 1) {
                    MetalLogger.info("[EntityRenderMixin] Entity frame end - {} entities, {} vertices",
                            entityRenderer.getEntitiesRenderedThisFrame(),
                            entityRenderer.getVerticesRenderedThisFrame());
                }
            }
        }
    }
}
