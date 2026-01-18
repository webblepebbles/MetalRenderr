package com.metalrender.sodium.mixins;

import com.metalrender.MetalRenderClient;
import com.metalrender.entity.EntityCaptureState;
import com.metalrender.entity.MetalEntityRenderer;
import com.metalrender.nativebridge.NativeBridge;
import com.metalrender.render.MetalWorldRenderer;
import net.minecraft.client.MinecraftClient;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.client.render.GameRenderer;

/**
 * Mixin to enable entity capture during hand/held item rendering.
 * 
 * The hand rendering happens AFTER WorldRenderer.render() completes,
 * which means EntityCaptureState is disabled. We need to re-enable it
 * during renderHand() so the held item vertices get captured.
 * 
 * The hand uses a DIFFERENT projection matrix than the world:
 * - FOV of 70 degrees (fixed, not affected by game settings)
 * - Rendered as overlay on top of world
 * 
 * CRITICAL: We clear the depth buffer before hand rendering so the hand
 * and held items always appear in front of terrain (since Metal and OpenGL
 * cannot share depth buffers).
 */
@Mixin(GameRenderer.class)
public class HeldItemRendererMixin {

    private static int handFrameCount = 0;

    /**
     * Enable entity capture at the start of hand rendering.
     * Also set up the special hand projection matrix.
     * CLEAR DEPTH BUFFER so hand always renders in front.
     */
    @Inject(method = "renderHand", at = @At("HEAD"))
    private void metalrender$beforeRenderHand(float tickDelta, boolean lastGameRender, Matrix4f positionMatrix,
            CallbackInfo ci) {
        if (!MetalRenderClient.isEnabled())
            return;

        handFrameCount++;

        // CRITICAL: Clear depth buffer so hand/items render in front of terrain
        // This fixes the issue where held items appear behind terrain
        MetalWorldRenderer worldRenderer = MetalRenderClient.getWorldRenderer();
        if (worldRenderer != null && worldRenderer.isReady()) {
            long handle = worldRenderer.getHandle();
            if (handle != 0) {
                NativeBridge.nClearDepthForHand(handle);
            }
        }

        // Re-enable entity capture during hand rendering
        EntityCaptureState.enable();

        // Set up special hand projection
        // The hand uses a fixed 70 degree FOV perspective projection
        MetalEntityRenderer entityRenderer = MetalEntityRenderer.getInstance();
        if (entityRenderer != null) {
            // CRITICAL: Clear any leftover texture from world entity rendering
            // so hand gets its own texture from render layer
            entityRenderer.getVertexConsumer().setTexture(null);

            MinecraftClient client = MinecraftClient.getInstance();
            float aspectRatio = (float) client.getWindow().getFramebufferWidth() /
                    (float) client.getWindow().getFramebufferHeight();

            // Create hand projection: 70 FOV, aspect ratio, near=0.05, far=1000
            // This matches what Minecraft uses for first-person view
            Matrix4f handProjection = new Matrix4f();
            handProjection.perspective(
                    (float) Math.toRadians(70.0), // Fixed 70 degree FOV
                    aspectRatio,
                    0.05f, // Near plane
                    1000.0f // Far plane
            );

            // The positionMatrix from GameRenderer already includes the hand's model-view
            // transform
            // We need to combine it with our projection
            Matrix4f viewProj = new Matrix4f();
            viewProj.set(handProjection);
            viewProj.mul(positionMatrix);

            entityRenderer.setOverrideProjection(viewProj);
        }
    }

    /**
     * Disable entity capture at the end of hand rendering and render the captured
     * hand mesh.
     */
    @Inject(method = "renderHand", at = @At("RETURN"))
    private void metalrender$afterRenderHand(float tickDelta, boolean lastGameRender, Matrix4f positionMatrix,
            CallbackInfo ci) {
        if (!MetalRenderClient.isEnabled())
            return;

        // Get the entity renderer and harvest the hand mesh
        MetalEntityRenderer entityRenderer = MetalEntityRenderer.getInstance();
        if (entityRenderer != null) {
            // Harvest captured hand vertices
            entityRenderer.endCapture();

            // Render the hand to Metal (uses the override projection we set)
            entityRenderer.renderEntities();

            // Clear the override projection
            entityRenderer.setOverrideProjection(null);
        }

        // Disable entity capture after hand rendering
        EntityCaptureState.disable();
    }
}
