package com.metalrender.sodium.mixins;

import com.metalrender.MetalRenderClient;
import com.metalrender.entity.MetalEntityRenderer;
import com.metalrender.render.unified.MetalRenderCoordinator;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin to capture block entity rendering for Metal.
 * 
 * Hooks into block entity rendering to capture vertex data.
 * Uses a simplified approach targeting BlockEntityRenderer directly.
 */
@Mixin(BlockEntityRenderer.class)
public interface BlockEntityRenderMixin<T extends BlockEntity> {

    /**
     * Hook before rendering a block entity.
     * Using interface mixin for broader compatibility.
     */
    @Inject(method = "render", at = @At("HEAD"), require = 0)
    default void metalrender$beforeRender(
            T blockEntity,
            float tickDelta,
            MatrixStack matrices,
            VertexConsumerProvider vertexConsumers,
            int light,
            int overlay,
            CallbackInfo ci) {

        if (!MetalRenderClient.isEnabled())
            return;

        MetalRenderCoordinator coordinator = MetalRenderCoordinator.getInstance();
        if (coordinator == null || !coordinator.isInitialized())
            return;

        MetalEntityRenderer entityRenderer = coordinator.getEntityRenderer();
        if (entityRenderer == null || !entityRenderer.isEnabled())
            return;

        // Set up block entity transform
        MatrixStack.Entry entry = matrices.peek();
        Matrix4f positionMatrix = entry.getPositionMatrix();
        Matrix3f normalMatrix = entry.getNormalMatrix();

        entityRenderer.setEntityTransform(positionMatrix, normalMatrix);
    }
}
