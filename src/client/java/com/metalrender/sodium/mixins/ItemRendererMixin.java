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

@Mixin(ItemRenderer.class)
public abstract class ItemRendererMixin {

    private static int renderCount = 0;

    
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
