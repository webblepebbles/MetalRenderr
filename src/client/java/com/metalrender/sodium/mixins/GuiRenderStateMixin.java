package com.metalrender.sodium.mixins;

import com.metalrender.MetalRenderClient;
import com.metalrender.render.gui.MetalGuiRenderer;
import com.metalrender.render.text.MetalTextRenderer;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.client.gui.render.state.ItemGuiElementRenderState;
import net.minecraft.client.gui.render.state.TextGuiElementRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin to intercept deferred text rendering in MC 1.21.
 * 
 * In Minecraft 1.21, many UI elements (buttons, widgets) use DrawnTextConsumer
 * which queues TextGuiElementRenderState to GuiRenderState for deferred
 * rendering.
 * 
 * This path does NOT go through DrawContext.drawText()!
 * 
 * Flow:
 * 1. Button calls drawTextWithMargin(DrawnTextConsumer, Text, margin)
 * 2. DrawnTextConsumer (TextConsumerImpl) creates TextGuiElementRenderState
 * 3. GuiRenderState.addText() is called to queue the text
 * 4. Later, GuiRenderer.render() processes all queued text
 * 
 * We intercept at step 3 to render the text via Metal.
 */
@Mixin(GuiRenderState.class)
public class GuiRenderStateMixin {

    // Static initializer to confirm mixin is loaded
    static {
        System.out.println("[Metal] GuiRenderStateMixin CLASS LOADED");
    }

    private static int deferredTextCount = 0;
    private static long lastLogTime = 0;

    /**
     * Intercept addText to render deferred text via Metal.
     * This catches button labels, widget text, and other DrawnTextConsumer text.
     */
    @Inject(method = "addText", at = @At("HEAD"), cancellable = true)
    private void metalrender$interceptAddText(TextGuiElementRenderState textState, CallbackInfo ci) {
        if (!MetalRenderClient.isEnabled())
            return;

        // Only intercept if we are in-game (world is loaded)
        // This leaves Main Menu, Loading Screen, etc. to Vanilla OpenGL
        net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
        if (client.world == null)
            return;

        MetalTextRenderer metalText = MetalTextRenderer.getInstance();
        if (!metalText.isEnabled())
            return;

        // Ensure GUI frame is started with proper dimensions
        MetalGuiRenderer gui = MetalGuiRenderer.getInstance();
        if (!gui.isEnabled())
            return;

        // Check screen dimensions
        int screenW = gui.getScreenWidth();
        int screenH = gui.getScreenHeight();

        if (screenW <= 0 || screenH <= 0) {
            // Try to start frame with current window dimensions
            gui.ensureFrameStarted();
            screenW = gui.getScreenWidth();
            screenH = gui.getScreenHeight();
        }

        if (screenW <= 0 || screenH <= 0) {
            // Still no dimensions - let vanilla handle it
            // This means we're being called before the frame is started
            deferredTextCount++;
            long now = System.currentTimeMillis();
            if (now - lastLogTime > 5000) {
                System.out.println("[Metal] WARNING: Deferred text skipped (no dimensions): " + deferredTextCount
                        + " items, screen=" + screenW + "x" + screenH);
                lastLogTime = now;
                deferredTextCount = 0;
            }
            return;
        }

        try {
            // Extract text data from the render state
            int x = textState.x;
            int y = textState.y;
            int color = textState.color;
            boolean shadow = textState.shadow;

            // Debug first few text renders
            if (deferredTextCount < 5) {
                StringBuilder sb = new StringBuilder();
                textState.orderedText.accept((i, s, c) -> {
                    sb.appendCodePoint(c);
                    return true;
                });
                System.out.println("[Metal] Deferred text at (" + x + "," + y + ") color=0x" +
                        Integer.toHexString(color) + " screen=" + screenW + "x" + screenH + " text='" + sb + "'");
            }

            // Render via Metal
            metalText.drawOrderedText(
                    textState.textRenderer,
                    textState.orderedText,
                    x, y, color, shadow);

            deferredTextCount++;

            // Debug logging
            long now = System.currentTimeMillis();
            if (now - lastLogTime > 5000) {
                System.out.println("[Metal] Deferred text rendered via Metal: " + deferredTextCount + " items");
                lastLogTime = now;
                deferredTextCount = 0;
            }

            // DON'T cancel vanilla rendering yet - Metal text glyph rendering is incomplete
            // Once we have proper font atlas + glyph rendering, we can uncomment this:
            // ci.cancel();

        } catch (Exception e) {
            // Fall through to vanilla if there's an error
            System.err.println("[Metal] Error intercepting deferred text: " + e.getMessage());
        }
    }

    // ========== ITEM RENDERING ==========
    private static int deferredItemCount = 0;
    private static long lastItemLogTime = 0;

    /**
     * Intercept addItem to render items via Metal.
     * Items are normally rendered by GuiRenderer.prepareItemElements() via OpenGL,
     * but we need to render them via Metal to prevent them being overwritten by our
     * blit.
     */
    @Inject(method = "addItem", at = @At("HEAD"), cancellable = true)
    private void metalrender$interceptAddItem(ItemGuiElementRenderState itemState, CallbackInfo ci) {
        if (!MetalRenderClient.isEnabled())
            return;

        // Only intercept in-game
        net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
        if (client.world == null)
            return;

        MetalGuiRenderer gui = MetalGuiRenderer.getInstance();
        if (!gui.isEnabled())
            return;

        deferredItemCount++;

        // Get item position
        int x = itemState.x();
        int y = itemState.y();

        // Items in GUI are 16x16 pixels
        int itemSize = 16;

        // Log first few items
        if (deferredItemCount <= 20) {
            String itemName = itemState.name();
            System.out.println("[Metal] Rendering item via Metal: '" + itemName +
                    "' at (" + x + "," + y + ")");
        }

        // Try to get the item's particle sprite for texture
        try {
            net.minecraft.client.render.item.KeyedItemRenderState renderState = itemState.state();
            if (renderState != null && !renderState.isEmpty()) {
                // Get first layer's particle sprite as a fallback texture
                net.minecraft.client.texture.Sprite sprite = renderState.getParticleSprite(
                        net.minecraft.util.math.random.Random.create());

                if (sprite != null) {
                    // Use sprite's atlas for actual item textures
                    gui.setTexture(sprite.getAtlasId());

                    // Get UV coordinates from sprite
                    float u1 = sprite.getMinU();
                    float v1 = sprite.getMinV();
                    float u2 = sprite.getMaxU();
                    float v2 = sprite.getMaxV();

                    // Render the item as a textured quad
                    gui.addQuad(x, y, x + itemSize, y + itemSize, 0, u1, v1, u2, v2, 0xFFFFFFFF);

                    if (deferredItemCount <= 5) {
                        System.out.println("[Metal] Item sprite: " + sprite.getAtlasId() +
                                " UV: " + u1 + "," + v1 + " -> " + u2 + "," + v2);
                    }
                } else {
                    // No sprite - render as a colored placeholder
                    gui.setTexture(null);
                    gui.addQuad(x, y, x + itemSize, y + itemSize, 0, 0, 0, 1, 1, 0xFF00FF00); // Green
                }
            } else {
                // Empty render state - render placeholder
                gui.setTexture(null);
                gui.addQuad(x, y, x + itemSize, y + itemSize, 0, 0, 0, 1, 1, 0xFF0000FF); // Blue
            }
        } catch (Exception e) {
            // Fallback: render a colored placeholder
            if (deferredItemCount <= 5) {
                System.out.println("[Metal] Item render error: " + e.getMessage());
            }
            gui.setTexture(null);
            gui.addQuad(x, y, x + itemSize, y + itemSize, 0, 0, 0, 1, 1, 0xFFFF0000); // Red
        }

        // CANCEL vanilla item rendering - we're handling it via Metal
        ci.cancel();
    }
}
