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

@Mixin(GameRenderer.class)
public class HeldItemRendererMixin {

    private static int handFrameCount = 0;

    
    @Inject(method = "renderHand", at = @At("HEAD"))
    private void metalrender$beforeRenderHand(float tickDelta, boolean lastGameRender, Matrix4f positionMatrix,
            CallbackInfo ci) {
        if (!MetalRenderClient.isEnabled())
            return;

        handFrameCount++;
        MetalWorldRenderer worldRenderer = MetalRenderClient.getWorldRenderer();
        if (worldRenderer != null && worldRenderer.isReady()) {
            long handle = worldRenderer.getHandle();
            if (handle != 0) {
                NativeBridge.nClearDepthForHand(handle);
            }
        }
        EntityCaptureState.enable();
        MetalEntityRenderer entityRenderer = MetalEntityRenderer.getInstance();
        if (entityRenderer != null) {
            entityRenderer.getVertexConsumer().setTexture(null);

            MinecraftClient client = MinecraftClient.getInstance();
            float aspectRatio = (float) client.getWindow().getFramebufferWidth() /
                    (float) client.getWindow().getFramebufferHeight();
            Matrix4f handProjection = new Matrix4f();
            handProjection.perspective(
                    (float) Math.toRadians(70.0), 
                    aspectRatio,
                    0.05f, 
                    1000.0f 
            );
            Matrix4f viewProj = new Matrix4f();
            viewProj.set(handProjection);
            viewProj.mul(positionMatrix);

            entityRenderer.setOverrideProjection(viewProj);
        }
    }

    
    @Inject(method = "renderHand", at = @At("RETURN"))
    private void metalrender$afterRenderHand(float tickDelta, boolean lastGameRender, Matrix4f positionMatrix,
            CallbackInfo ci) {
        if (!MetalRenderClient.isEnabled())
            return;
        MetalEntityRenderer entityRenderer = MetalEntityRenderer.getInstance();
        if (entityRenderer != null) {
            entityRenderer.endCapture();
            entityRenderer.renderEntities();
            entityRenderer.setOverrideProjection(null);
        }
        EntityCaptureState.disable();
    }
}
