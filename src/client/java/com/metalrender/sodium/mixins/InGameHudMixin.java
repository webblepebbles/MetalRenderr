package com.metalrender.sodium.mixins;

import com.metalrender.MetalRenderClient;
import com.metalrender.entity.EntityCaptureState;
import com.metalrender.render.MetalWorldRenderer;
import com.metalrender.render.gui.MetalGuiRenderer;
import com.metalrender.render.gui.MetalGuiVertexConsumer;
import com.metalrender.render.unified.MetalRenderCoordinator;
import com.metalrender.nativebridge.NativeBridge;
import com.metalrender.util.MetalLogger;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 100% Metal GUI rendering.
 * Begins Metal GUI frame before HUD, ends after.
 * All draw calls are intercepted by DrawContextMixin.
 * Text rendering captured via EntityCaptureState + VertexConsumerProviderMixin.
 */
@Mixin(InGameHud.class)
public class InGameHudMixin {

    private static int frameCount = 0;

    @Inject(method = "render", at = @At("HEAD"))
    private void metalrender$beforeHudRender(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        if (MetalRenderClient.isEnabled()) {
            frameCount++;

            // NOTE: Blit moved back to RenderSystemFlipMixin - terrain blit here was too
            // late

            // Begin Metal GUI frame - all draw calls will go to Metal
            MetalGuiRenderer gui = MetalGuiRenderer.getInstance();
            if (gui.isEnabled()) {
                MinecraftClient client = MinecraftClient.getInstance();
                int width = client.getWindow().getScaledWidth();
                int height = client.getWindow().getScaledHeight();
                gui.beginFrame(width, height);

                // Begin text vertex consumer frame
                MetalGuiVertexConsumer.getInstance().beginFrame();

                // Begin item renderer frame for capturing item vertices
                MetalRenderCoordinator coordinator = MetalRenderCoordinator.getInstance();
                if (coordinator != null && coordinator.isInitialized()) {
                    // Use identity matrices for GUI item rendering
                    org.joml.Matrix4f identity = new org.joml.Matrix4f().identity();
                    coordinator.getItemRenderer().beginFrame(identity, 0, 0, 0);
                }

                // Enable entity capture for text rendering (GUI mode)
                EntityCaptureState.setEnabled(true);

                if (frameCount <= 5 || frameCount % 60 == 0) {
                    MetalLogger.info("[InGameHudMixin] Metal GUI frame started, %dx%d", width, height);
                }
            }
        }
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void metalrender$afterHudRender(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        if (MetalRenderClient.isEnabled()) {
            // Disable entity capture for text
            EntityCaptureState.setEnabled(false);

            // Flush item vertices captured during HUD rendering
            MetalRenderCoordinator coordinator = MetalRenderCoordinator.getInstance();
            if (coordinator != null && coordinator.isInitialized()) {
                coordinator.getItemRenderer().finishItem();
                coordinator.getItemRenderer().endCapture();
                coordinator.getItemRenderer().renderItems(); // Actually render the items!
            }

            // End Metal GUI frame - submits all GUI to Metal
            MetalGuiRenderer gui = MetalGuiRenderer.getInstance();
            if (gui.isEnabled()) {
                gui.endFrame();

                if (frameCount <= 5 || frameCount % 60 == 0) {
                    MetalLogger.info("[InGameHudMixin] Metal GUI frame ended");
                }
            }
        }
    }
}
