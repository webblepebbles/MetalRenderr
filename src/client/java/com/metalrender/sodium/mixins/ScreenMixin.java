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

/**
 * Mixin to capture Screen (menu) rendering for Metal.
 * 
 * Screens (like the ESC menu, inventory, etc.) render separately from
 * InGameHud.
 * We need to ensure the GUI frame is properly ended after screen rendering.
 * Text rendering is captured via EntityCaptureState +
 * VertexConsumerProviderMixin.
 * Item rendering is captured via MetalItemRenderer.
 */
@Mixin(Screen.class)
public class ScreenMixin {

    private static int screenRenderCount = 0;

    /**
     * Before screen render - ensure GUI frame is started and enable text capture.
     */
    @Inject(method = "render", at = @At("HEAD"))
    private void metalrender$beforeScreenRender(DrawContext context, int mouseX, int mouseY, float delta,
            CallbackInfo ci) {
        // Metal rendering enabled for screens/menus
        if (!MetalRenderClient.isEnabled())
            return;

        screenRenderCount++;
        if (screenRenderCount <= 10 || screenRenderCount % 60 == 0) {
            System.out.println("[ScreenMixin] beforeScreenRender #" + screenRenderCount + " screen="
                    + ((Screen) (Object) this).getClass().getSimpleName());
        }

        MetalGuiRenderer gui = MetalGuiRenderer.getInstance();
        if (gui.isEnabled()) {
            // Ensure GUI frame is started
            gui.ensureFrameStarted();

            // Begin text vertex consumer frame if not already started
            MetalGuiVertexConsumer.getInstance().beginFrame();

            // Begin item renderer frame for capturing inventory item vertices
            MetalRenderCoordinator coordinator = MetalRenderCoordinator.getInstance();
            if (coordinator != null && coordinator.isInitialized()) {
                MetalItemRenderer itemRenderer = coordinator.getItemRenderer();
                if (itemRenderer != null && itemRenderer.isEnabled()) {
                    // Use identity matrices for GUI item rendering (screen-space)
                    Matrix4f identity = new Matrix4f().identity();
                    itemRenderer.beginFrame(identity, 0, 0, 0);

                    if (screenRenderCount <= 10 || screenRenderCount % 60 == 0) {
                        System.out.println("[ScreenMixin] Item renderer frame started for inventory items");
                    }
                }
            }

            // Enable entity capture for text rendering (GUI mode)
            EntityCaptureState.setEnabled(true);
        }
    }

    /**
     * After screen render - flush and render the GUI.
     */
    @Inject(method = "render", at = @At("TAIL"))
    private void metalrender$afterScreenRender(DrawContext context, int mouseX, int mouseY, float delta,
            CallbackInfo ci) {
        // Metal rendering enabled for screens/menus
        if (!MetalRenderClient.isEnabled())
            return;

        // Disable entity capture for text
        EntityCaptureState.setEnabled(false);

        // Get the current screen type
        Screen currentScreen = (Screen) (Object) this;
        String screenClass = currentScreen.getClass().getSimpleName();

        // For HandledScreens (inventory, chests, etc), items are drawn during
        // renderContent
        // which happens INSIDE render(), so we should be fine ending the frame here.
        // However, if items still don't show, we might need to move endFrame elsewhere.

        // Flush and render item vertices captured during screen rendering
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
            // DEBUG: Log pending mesh count before endFrame
            if (screenRenderCount <= 10 || screenRenderCount % 60 == 0) {
                System.out.println("[ScreenMixin] afterScreenRender #" + screenRenderCount +
                        " screen=" + screenClass + " calling endFrame");
            }
            // End the GUI frame to submit all captured draw calls to Metal
            gui.endFrame();
        }
    }
}
