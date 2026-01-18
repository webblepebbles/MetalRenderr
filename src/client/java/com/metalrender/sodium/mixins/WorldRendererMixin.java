package com.metalrender.sodium.mixins;

import com.metalrender.MetalRenderClient;
import com.metalrender.entity.EntityCaptureState;
import com.metalrender.entity.MetalEntityRenderer;
import com.metalrender.nativebridge.NativeBridge;
import com.metalrender.render.MetalSurfaceManager;
import com.metalrender.render.MetalWorldRenderer;
import com.metalrender.render.unified.MetalRenderCoordinator;
import com.metalrender.util.MetalLogger;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.ObjectAllocator;
import net.minecraft.client.render.FrameGraphBuilder;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.state.WorldRenderState;
import net.minecraft.util.profiler.Profiler;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
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

        // Entity capture - enable at start of render
        if (MetalRenderClient.isEnabled()) {
            entityFrameCount++;

            // Mark game as loaded after several successful frames in-world
            // This allows CAMetalLayer to be attached safely after initial loading is done
            if (entityFrameCount == 30) {
                MetalSurfaceManager.markGameLoaded();
            }

            // Build view-projection matrix
            Matrix4f viewProj = new Matrix4f();
            projectionMatrix.mul(positionMatrix, viewProj);

            // Begin frame for all renderers via coordinator
            MetalRenderCoordinator coordinator = MetalRenderCoordinator.getInstance();
            if (coordinator != null && coordinator.isInitialized()) {
                coordinator.beginFrame(camera, viewProj);
            }

            // Enable vertex capture for entities
            EntityCaptureState.enable();

            // CRITICAL: Ensure CAMetalLayer is attached for direct presentation
            MetalWorldRenderer renderer = MetalRenderClient.getWorldRenderer();
            if (renderer != null) {
                long handle = renderer.getHandle();
                if (handle != 0) {
                    MetalSurfaceManager.ensureSurface(handle);
                }
            }
        }

        // Original solo mode logic
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

        // End frame for all renderers
        if (MetalRenderClient.isEnabled()) {
            // Disable vertex capture
            EntityCaptureState.disable();

            // End frame via coordinator - this handles entities, items, particles
            MetalRenderCoordinator coordinator = MetalRenderCoordinator.getInstance();
            if (coordinator != null && coordinator.isInitialized()) {
                if (renderEndCallCount <= 20 || renderEndCallCount % 500 == 0) {
                    System.out.println("[WorldRendererMixin] renderEnd #" + renderEndCallCount +
                            ": calling coordinator.renderEntities()");
                }
                coordinator.renderEntities();
                coordinator.renderItems();
                // coordinator.renderParticles(); // DISABLED: Let vanilla render particles
                coordinator.endFrame();
            } else {
                if (renderEndCallCount <= 20) {
                    System.out.println("[WorldRendererMixin] renderEnd #" + renderEndCallCount +
                            ": coordinator null or not initialized");
                }
            }

            // NO BLIT HERE - blit happens at flipFrame AFTER GUI renders to Metal
            // Metal renders everything (terrain + GUI), then blits once at end
        } else {
            if (renderEndCallCount <= 20) {
                System.out.println("[WorldRendererMixin] renderEnd #" + renderEndCallCount +
                        ": MetalRenderClient NOT enabled");
            }
        }
    }
}
