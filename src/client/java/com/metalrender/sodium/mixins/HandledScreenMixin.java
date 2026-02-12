package com.metalrender.sodium.mixins;

import com.metalrender.MetalRenderClient;
import com.metalrender.entity.EntityCaptureState;
import com.metalrender.render.gui.MetalGuiRenderer;
import com.metalrender.render.gui.MetalGuiVertexConsumer;
import com.metalrender.render.item.MetalItemRenderer;
import com.metalrender.render.unified.MetalRenderCoordinator;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(HandledScreen.class)
public class HandledScreenMixin {

    private static int handledScreenRenderCount = 0;

    
    @Inject(method = "render", at = @At("HEAD"))
    private void metalrender$beforeHandledScreenRender(DrawContext context, int mouseX, int mouseY, float delta,
            CallbackInfo ci) {
        if (!MetalRenderClient.isEnabled())
            return;

        handledScreenRenderCount++;
        String screenClass = ((HandledScreen<?>) (Object) this).getClass().getSimpleName();

        if (handledScreenRenderCount <= 30 || handledScreenRenderCount % 60 == 0) {
            System.out.println("[HandledScreenMixin] beforeRender #" + handledScreenRenderCount +
                    " screen=" + screenClass);
        }

        MetalGuiRenderer gui = MetalGuiRenderer.getInstance();
        if (gui.isEnabled()) {
            gui.ensureFrameStarted();
            MetalGuiVertexConsumer.getInstance().beginFrame();
            MetalRenderCoordinator coordinator = MetalRenderCoordinator.getInstance();
            if (coordinator != null && coordinator.isInitialized()) {
                MetalItemRenderer itemRenderer = coordinator.getItemRenderer();
                if (itemRenderer != null && itemRenderer.isEnabled()) {
                    Matrix4f identity = new Matrix4f().identity();
                    itemRenderer.beginFrame(identity, 0, 0, 0);
                }
            }
            EntityCaptureState.setEnabled(true);
        }
    }

    
    @Inject(method = "render", at = @At("TAIL"))
    private void metalrender$afterHandledScreenRender(DrawContext context, int mouseX, int mouseY, float delta,
            CallbackInfo ci) {
        if (!MetalRenderClient.isEnabled())
            return;

        String screenClass = ((HandledScreen<?>) (Object) this).getClass().getSimpleName();
        EntityCaptureState.setEnabled(false);
        MetalRenderCoordinator coordinator = MetalRenderCoordinator.getInstance();
        if (coordinator != null && coordinator.isInitialized()) {
            MetalItemRenderer itemRenderer = coordinator.getItemRenderer();
            if (itemRenderer != null && itemRenderer.isEnabled()) {
                itemRenderer.finishItem();
                itemRenderer.endCapture();
                itemRenderer.renderItems();

                if (handledScreenRenderCount <= 30 || handledScreenRenderCount % 60 == 0) {
                    System.out.println("[HandledScreenMixin] Item renderer flushed for " + screenClass + ": " +
                            itemRenderer.getItemsRenderedThisFrame() + " items, " +
                            itemRenderer.getVerticesRenderedThisFrame() + " vertices");
                }
            }
        }

        MetalGuiRenderer gui = MetalGuiRenderer.getInstance();
        if (gui.isEnabled()) {
            if (handledScreenRenderCount <= 30 || handledScreenRenderCount % 60 == 0) {
                System.out.println("[HandledScreenMixin] afterRender #" + handledScreenRenderCount +
                        " screen=" + screenClass + " calling endFrame");
            }
            gui.endFrame();
        }
    }
}
