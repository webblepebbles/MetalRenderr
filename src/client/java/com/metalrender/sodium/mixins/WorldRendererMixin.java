package com.metalrender.sodium.mixins;

import com.metalrender.MetalRenderClient;
import com.metalrender.entity.EntityCaptureState;
import com.metalrender.render.MetalSurfaceManager;
import com.metalrender.render.MetalWorldRenderer;
import com.metalrender.render.unified.MetalRenderCoordinator;
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
public class WorldRendererMixin {

    private static int entityFrameCount = 0;

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void metalrender$render(ObjectAllocator allocator, RenderTickCounter tickCounter,
            boolean renderBlockOutline,
            Camera camera, Matrix4f positionMatrix, Matrix4f projectionMatrix, Matrix4f matrix4f3,
            GpuBufferSlice bufferSlice, Vector4f vector4f, boolean bl, CallbackInfo ci) {
        if (MetalRenderClient.isEnabled()) {
            entityFrameCount++;
            if (entityFrameCount == 30) {
                MetalSurfaceManager.markGameLoaded();
            }
            Matrix4f viewProj = new Matrix4f();
            projectionMatrix.mul(positionMatrix, viewProj);
            MetalRenderCoordinator coordinator = MetalRenderCoordinator.getInstance();
            if (coordinator != null && coordinator.isInitialized()) {
                coordinator.beginFrame(camera, viewProj);
            }
            EntityCaptureState.enable();
            MetalWorldRenderer renderer = MetalRenderClient.getWorldRenderer();
            if (renderer != null) {
                long handle = renderer.getHandle();
                if (handle != 0) {
                    MetalSurfaceManager.ensureSurface(handle);
                }
            }
        }
        if (MetalRenderClient.isEnabled() && MetalRenderClient.issodiumless()) {
            MetalWorldRenderer renderer = MetalRenderClient.getWorldRenderer();
            if (renderer != null) {
                renderer.renderFrame(null, null, 0, 0, 0);
                ci.cancel();
            }
        }
    }

    private static int renderEndCallCount = 0;

    @Inject(method = "render", at = @At("RETURN"))
    private void metalrender$renderEnd(ObjectAllocator allocator, RenderTickCounter tickCounter,
            boolean renderBlockOutline,
            Camera camera, Matrix4f positionMatrix, Matrix4f projectionMatrix, Matrix4f matrix4f3,
            GpuBufferSlice bufferSlice, Vector4f vector4f, boolean bl, CallbackInfo ci) {

        renderEndCallCount++;
        if (MetalRenderClient.isEnabled()) {
            EntityCaptureState.disable();
            MetalRenderCoordinator coordinator = MetalRenderCoordinator.getInstance();
            if (coordinator != null && coordinator.isInitialized()) {
                coordinator.renderEntities();
                coordinator.endFrame();
            }
        }
    }
}
