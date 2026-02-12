package com.metalrender.sodium.mixins;

import com.metalrender.MetalRenderClient;
import com.metalrender.entity.EntityCaptureState;
import com.metalrender.entity.MetalEntityRenderer;
import com.metalrender.render.unified.MetalRenderCoordinator;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin<S extends LivingEntityRenderState> {
    @Shadow
    public abstract Identifier getTexture(S state);

    private static int renderCount = 0;
    private static int logCount = 0;

    static {
        System.out.println("[LivingEntityRendererMixin] *** STATIC INIT - MIXIN LOADED ***");
    }

    
    @Inject(method = "render", at = @At("HEAD"), require = 0)
    private void metalrender$afterTransforms(
            S state,
            MatrixStack matrixStack,
            OrderedRenderCommandQueue queue,
            CameraRenderState cameraState,
            CallbackInfo ci) {

        renderCount++;
        if (logCount < 50) {
            logCount++;
            System.out.println("[LivingEntityRendererMixin] *** RENDER CALLED *** renderCount=" + renderCount);
            System.out.println(
                    "[LivingEntityRendererMixin]   MetalRenderClient.isEnabled()=" + MetalRenderClient.isEnabled());
            System.out.println(
                    "[LivingEntityRendererMixin]   EntityCaptureState.isEnabled()=" + EntityCaptureState.isEnabled());
        }

        if (!MetalRenderClient.isEnabled())
            return;
        if (!EntityCaptureState.isEnabled())
            return;

        MetalRenderCoordinator coordinator = MetalRenderCoordinator.getInstance();
        if (coordinator == null || !coordinator.isInitialized()) {
            if (logCount < 20) {
                System.out.println("[LivingEntityRendererMixin]   coordinator not ready, skipping");
            }
            return;
        }

        MetalEntityRenderer entityRenderer = coordinator.getEntityRenderer();
        if (entityRenderer == null || !entityRenderer.isEnabled()) {
            if (logCount < 20) {
                System.out.println("[LivingEntityRendererMixin]   entityRenderer not enabled, skipping");
            }
            return;
        }

        if (logCount < 20) {
            System.out.println("[LivingEntityRendererMixin]   CAPTURING entity transform and texture!");
        }
        MatrixStack.Entry entry = matrixStack.peek();
        Matrix4f positionMatrix = entry.getPositionMatrix();
        Matrix3f normalMatrix = entry.getNormalMatrix();
        Identifier texture = this.getTexture(state);
        if (texture != null) {
            entityRenderer.setEntityTexture(texture);
            if (logCount < 20) {
                System.out.println("[LivingEntityRendererMixin]   Entity texture: " + texture);
            }
        }

        entityRenderer.setEntityTransform(positionMatrix, normalMatrix);
    }

    
    @Inject(method = "render(Lnet/minecraft/client/render/entity/state/LivingEntityRenderState;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;Lnet/minecraft/client/render/state/CameraRenderState;)V", at = @At("RETURN"), require = 0)
    private void metalrender$afterRender(
            S state,
            MatrixStack matrixStack,
            OrderedRenderCommandQueue queue,
            CameraRenderState cameraState,
            CallbackInfo ci) {

        if (!MetalRenderClient.isEnabled())
            return;
        if (!EntityCaptureState.isEnabled())
            return;

        MetalRenderCoordinator coordinator = MetalRenderCoordinator.getInstance();
        if (coordinator == null || !coordinator.isInitialized())
            return;

        MetalEntityRenderer entityRenderer = coordinator.getEntityRenderer();
        if (entityRenderer == null || !entityRenderer.isEnabled())
            return;
        entityRenderer.finishEntity();
    }
}
