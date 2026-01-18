package com.metalrender.sodium.mixins;

import com.metalrender.MetalRenderClient;
import com.metalrender.entity.EntityCaptureState;
import com.metalrender.entity.MetalEntityRenderer;
import com.metalrender.render.unified.MetalRenderCoordinator;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.EntityRenderManager;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin to capture ALL entity rendering via EntityRenderManager.
 * This is the central dispatch point for entity rendering in MC 1.21.11.
 * 
 * Method: render(S, CameraRenderState, double, double, double, MatrixStack,
 * OrderedRenderCommandQueue)
 */
@Mixin(EntityRenderManager.class)
public class EntityRenderManagerMixin {

    private static int renderCount = 0;
    private static int logCount = 0;

    static {
        System.out.println("[EntityRenderManagerMixin] *** STATIC INIT - MIXIN LOADED ***");
    }

    /**
     * Hook into EntityRenderManager.render() which is called for ALL entities.
     */
    @Inject(method = "render", at = @At("HEAD"), require = 0)
    private <S extends EntityRenderState> void metalrender$captureEntityRender(
            S state,
            CameraRenderState cameraState,
            double x, double y, double z,
            MatrixStack matrixStack,
            OrderedRenderCommandQueue queue,
            CallbackInfo ci) {

        renderCount++;

        // Debug logging - log first 100 calls
        if (logCount < 100) {
            logCount++;
            System.out.println("[EntityRenderManagerMixin] *** RENDER CALLED #" + renderCount + " ***");
            System.out.println("[EntityRenderManagerMixin]   state=" + state.getClass().getSimpleName());
            System.out.println("[EntityRenderManagerMixin]   pos=(" + x + ", " + y + ", " + z + ")");
        }
    }
}
