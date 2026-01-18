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

/**
 * Mixin specifically for HandledScreen (inventory, chest, crafting table,
 * etc.).
 * 
 * HandledScreen extends Screen but overrides render() and may not trigger
 * the Screen mixin properly. This mixin ensures inventory screens are captured.
 * 
 * HandledScreen hierarchy:
 * - InventoryScreen (player inventory)
 * - ChestScreen
 * - CraftingScreen
 * - FurnaceScreen
 * - etc.
 */
@Mixin(HandledScreen.class)
public class HandledScreenMixin {

    private static int handledScreenRenderCount = 0;

    /**
     * Before handled screen render - ensure GUI frame is started.
     */
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
            // Ensure GUI frame is started
            gui.ensureFrameStarted();

            // Begin text vertex consumer frame
            MetalGuiVertexConsumer.getInstance().beginFrame();

            // Begin item renderer frame for capturing inventory item vertices
            MetalRenderCoordinator coordinator = MetalRenderCoordinator.getInstance();
            if (coordinator != null && coordinator.isInitialized()) {
                MetalItemRenderer itemRenderer = coordinator.getItemRenderer();
                if (itemRenderer != null && itemRenderer.isEnabled()) {
                    Matrix4f identity = new Matrix4f().identity();
                    itemRenderer.beginFrame(identity, 0, 0, 0);
                }
            }

            // Enable entity capture for text rendering (GUI mode)
            EntityCaptureState.setEnabled(true);
        }
    }

    /**
     * After handled screen render - flush and render the GUI.
     */
    @Inject(method = "render", at = @At("TAIL"))
    private void metalrender$afterHandledScreenRender(DrawContext context, int mouseX, int mouseY, float delta,
            CallbackInfo ci) {
        if (!MetalRenderClient.isEnabled())
            return;

        String screenClass = ((HandledScreen<?>) (Object) this).getClass().getSimpleName();

        // Disable entity capture for text
        EntityCaptureState.setEnabled(false);

        // Flush and render item vertices
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
