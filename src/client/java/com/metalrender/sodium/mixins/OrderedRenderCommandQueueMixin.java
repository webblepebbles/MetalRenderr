package com.metalrender.sodium.mixins;

import com.metalrender.MetalRenderClient;
import com.metalrender.entity.EntityCaptureState;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.command.ModelCommandRenderer;
import net.minecraft.client.render.command.OrderedRenderCommandQueueImpl;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(OrderedRenderCommandQueueImpl.class)
public class OrderedRenderCommandQueueMixin {

    private static int modelCount = 0;
    private static int partCount = 0;
    private static int logCount = 0;

    static {
        System.out.println("[OrderedRenderCommandQueueMixin] *** STATIC INIT - MIXIN LOADED ***");
    }

    
    @Inject(method = "submitModel", at = @At("HEAD"), require = 0)
    private <S> void metalrender$captureModel(
            Model<? super S> model, S state,
            MatrixStack matrixStack,
            RenderLayer renderLayer,
            int light, int overlay, int color,
            Sprite sprite, int textureIndex,
            ModelCommandRenderer.CrumblingOverlayCommand crumbling,
            CallbackInfo ci) {

        modelCount++;

        if (logCount < 100) {
            logCount++;
            System.out.println("[OrderedRenderCommandQueueMixin] submitModel #" + modelCount);
            System.out.println("  model=" + model.getClass().getSimpleName());
            System.out.println("  state=" + (state != null ? state.getClass().getSimpleName() : "null"));
            System.out.println("  layer=" + (renderLayer != null
                    ? renderLayer.toString().substring(0, Math.min(60, renderLayer.toString().length()))
                    : "null"));
            System.out.println("  MetalEnabled=" + MetalRenderClient.isEnabled() + ", CaptureEnabled="
                    + EntityCaptureState.isEnabled());
        }
    }

    
    @Inject(method = "submitModelPart", at = @At("HEAD"), require = 0)
    private void metalrender$captureModelPart(
            ModelPart part,
            MatrixStack matrixStack,
            RenderLayer renderLayer,
            int light, int overlay,
            Sprite sprite,
            boolean visible, boolean hasPose,
            int color,
            ModelCommandRenderer.CrumblingOverlayCommand crumbling,
            int textureIndex,
            CallbackInfo ci) {

        partCount++;

        if (logCount < 100) {
            logCount++;
            System.out.println("[OrderedRenderCommandQueueMixin] submitModelPart #" + partCount);
            System.out.println("  part=" + part);
            System.out.println("  layer=" + (renderLayer != null
                    ? renderLayer.toString().substring(0, Math.min(60, renderLayer.toString().length()))
                    : "null"));
            System.out.println("  visible=" + visible + ", hasPose=" + hasPose);
        }
    }
}
