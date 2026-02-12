package com.metalrender.sodium.mixins;

import com.metalrender.MetalRenderClient;
import com.metalrender.entity.EntityCaptureState;
import com.metalrender.render.gui.MetalGuiRenderer;
import com.metalrender.render.gui.MetalGuiVertexConsumer;
import com.metalrender.render.unified.MetalRenderCoordinator;
import com.metalrender.render.item.MetalItemRenderer;
import com.metalrender.nativebridge.NativeBridge;
import com.metalrender.util.MetalLogger;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(InGameHud.class)
public class InGameHudMixin {

    private static int frameCount = 0;

    @Inject(method = "render", at = @At("HEAD"))
    private void metalrender$beforeHudRender(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        if (!MetalRenderClient.isEnabled())
            return;

        frameCount++;
        if (frameCount <= 5 || frameCount % 300 == 0) {
            MetalLogger.info("[InGameHudMixin] Option B: OpenGL handles HUD (frame %d)", frameCount);
        }
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void metalrender$afterHudRender(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        if (!MetalRenderClient.isEnabled())
            return;
    }
}
