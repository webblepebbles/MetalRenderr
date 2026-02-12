package com.metalrender.sodium.mixins;

import com.metalrender.MetalRenderClient;
import com.metalrender.entity.EntityCaptureState;
import com.metalrender.render.gui.MetalGuiRenderer;
import com.metalrender.render.gui.MetalGuiVertexConsumer;
import com.metalrender.render.item.MetalItemRenderer;
import com.metalrender.render.unified.MetalRenderCoordinator;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Screen.class)
public class ScreenMixin {

    private static int screenRenderCount = 0;

    
    @Inject(method = "render", at = @At("HEAD"))
    private void metalrender$beforeScreenRender(DrawContext context, int mouseX, int mouseY, float delta,
            CallbackInfo ci) {
        if (!MetalRenderClient.isEnabled())
            return;

        screenRenderCount++;
        if (screenRenderCount <= 10 || screenRenderCount % 60 == 0) {
            System.out.println("[ScreenMixin] beforeScreenRender #" + screenRenderCount + " screen="
                    + ((Screen) (Object) this).getClass().getSimpleName());
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

                    if (screenRenderCount <= 10 || screenRenderCount % 60 == 0) {
                        System.out.println("[ScreenMixin] Item renderer frame started for inventory items");
                    }
                }
            }
            EntityCaptureState.setEnabled(true);
        }
    }

    
    @Inject(method = "render", at = @At("TAIL"))
    private void metalrender$afterScreenRender(DrawContext context, int mouseX, int mouseY, float delta,
            CallbackInfo ci) {
        if (!MetalRenderClient.isEnabled())
            return;
        EntityCaptureState.setEnabled(false);
        Screen currentScreen = (Screen) (Object) this;
        String screenClass = currentScreen.getClass().getSimpleName();
        MetalRenderCoordinator coordinator = MetalRenderCoordinator.getInstance();
        if (coordinator != null && coordinator.isInitialized()) {
            MetalItemRenderer itemRenderer = coordinator.getItemRenderer();
            if (itemRenderer != null && itemRenderer.isEnabled()) {
                itemRenderer.finishItem();
                itemRenderer.endCapture();
                itemRenderer.renderItems();

                if (screenRenderCount <= 10 || screenRenderCount % 60 == 0) {
                    System.out.println("[ScreenMixin] Item renderer flushed: " +
                            itemRenderer.getItemsRenderedThisFrame() + " items, " +
                            itemRenderer.getVerticesRenderedThisFrame() + " vertices");
                }
            }
        }

        MetalGuiRenderer gui = MetalGuiRenderer.getInstance();
        if (gui.isEnabled()) {
            if (screenRenderCount <= 10 || screenRenderCount % 60 == 0) {
                System.out.println("[ScreenMixin] afterScreenRender #" + screenRenderCount +
                        " screen=" + screenClass + " calling endFrame");
            }
            gui.endFrame();
        }
    }
}
