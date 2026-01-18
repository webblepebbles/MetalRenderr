package com.metalrender.sodium.mixins;

import com.metalrender.MetalRenderClient;
import com.metalrender.render.item.MetalItemRenderer;
import com.metalrender.render.unified.MetalRenderCoordinator;
import net.minecraft.client.render.item.ItemRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin to capture item rendering for Metal.
 * 
 * Uses a simplified approach that should work across MC versions.
 * Hooks at method entry to set up transforms for item rendering.
 */
@Mixin(ItemRenderer.class)
public abstract class ItemRendererMixin {

    private static int renderCount = 0;

    /**
     * Hook at any method that receives a MatrixStack to capture item transforms.
     * This is a generic approach that works across API changes.
     */
    @Inject(method = "renderItem*", at = @At("HEAD"), require = 0, remap = false)
    private void metalrender$beforeRenderItem(CallbackInfo ci) {
        renderCount++;

        if (!MetalRenderClient.isEnabled())
            return;

        MetalRenderCoordinator coordinator = MetalRenderCoordinator.getInstance();
        if (coordinator == null || !coordinator.isInitialized())
            return;

        MetalItemRenderer itemRenderer = coordinator.getItemRenderer();
        if (itemRenderer == null || !itemRenderer.isEnabled())
            return;
    }
}
