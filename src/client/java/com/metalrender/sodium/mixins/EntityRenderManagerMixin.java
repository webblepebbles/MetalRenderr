package com.metalrender.sodium.mixins;

import com.metalrender.MetalRenderClient;
import com.metalrender.config.MetalRenderConfig;
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

@Mixin(EntityRenderManager.class)
public class EntityRenderManagerMixin {

    private static int renderCount = 0;
    private static int logCount = 0;
    private static int distanceCulledCount = 0;

    static {
        System.out.println("[EntityRenderManagerMixin] *** STATIC INIT - MIXIN LOADED ***");
    }

    @Inject(method = "render", at = @At("HEAD"), cancellable = true, require = 0)
    private <S extends EntityRenderState> void metalrender$captureEntityRender(
            S state,
            CameraRenderState cameraState,
            double x, double y, double z,
            MatrixStack matrixStack,
            OrderedRenderCommandQueue queue,
            CallbackInfo ci) {

        renderCount++;

        if (MetalRenderClient.isEnabled()) {
            double distSq = x * x + y * y + z * z;
            int maxChunks = MetalRenderConfig.entityRenderDistanceChunks();
            double maxBlockDist = maxChunks * 16.0;
            double maxDistSq = maxBlockDist * maxBlockDist;

            if (distSq > maxDistSq) {
                distanceCulledCount++;
                if (logCount < 10 || renderCount % 6000 == 0) {
                    System.out.println("[EntityRenderManagerMixin] Distance-culled entity at dist="
                            + Math.sqrt(distSq) + " (max=" + maxBlockDist + " blocks, "
                            + maxChunks + " chunks). Total culled=" + distanceCulledCount);
                }
                ci.cancel();
                return;
            }
        }

        if (logCount < 100) {
            logCount++;
            System.out.println("[EntityRenderManagerMixin] *** RENDER CALLED #" + renderCount + " ***");
            System.out.println("[EntityRenderManagerMixin]   state=" + state.getClass().getSimpleName());
            System.out.println("[EntityRenderManagerMixin]   pos=(" + x + ", " + y + ", " + z + ")");
        }
    }
}
