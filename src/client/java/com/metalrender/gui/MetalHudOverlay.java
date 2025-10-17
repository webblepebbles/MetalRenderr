package com.metalrender.gui;

import com.metalrender.MetalRenderClient;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;

public final class MetalHudOverlay implements HudRenderCallback {
   private static final int COLOR = -65281;
   private static final String LABEL = "MetalRender ACTIVE";

   public void onHudRender(DrawContext context, RenderTickCounter tickCounter) {
      if (MetalRenderClient.isEnabled() && MetalRenderClient.getWorldRenderer() != null && MetalRenderClient.getWorldRenderer().isReady()) {
         TextRenderer textRenderer = MinecraftClient.getInstance().textRenderer;
         int x = 10;
         int y = 10;
         context.drawTextWithShadow(textRenderer, "MetalRender ACTIVE", x, y, -65281);
      }
   }

   public static void register() {
      HudRenderCallback.EVENT.register(new MetalHudOverlay());
   }
}
