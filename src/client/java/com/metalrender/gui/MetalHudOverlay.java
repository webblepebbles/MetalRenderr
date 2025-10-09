package com.metalrender.gui;

import com.metalrender.MetalRenderClient;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;

@SuppressWarnings("deprecation")
public final class MetalHudOverlay implements HudRenderCallback {
    private static final int COLOR = 0xFFFF00FF; 
    private static final String LABEL = "MetalRender ACTIVE";

    @Override
    public void onHudRender(DrawContext context, RenderTickCounter tickCounter) {
        if (!MetalRenderClient.isEnabled() || MetalRenderClient.getWorldRenderer() == null
            || !MetalRenderClient.getWorldRenderer().isReady()) {
            return;
        }
        var textRenderer = MinecraftClient.getInstance().textRenderer;
        int x = 10;
        int y = 10;
        context.drawTextWithShadow(textRenderer, LABEL, x, y, COLOR);
    }

    public static void register() {
        HudRenderCallback.EVENT.register(new MetalHudOverlay());
    }
}
