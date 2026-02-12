package com.metalrender.sodium.mixins;

import com.metalrender.MetalRenderClient;
import com.metalrender.render.MetalWorldRenderer;
import com.metalrender.util.MetalLogger;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(InGameHud.class)
public class StatusEffectOverlayMixin {

    @Unique
    private static int metalrender$frameCount = 0;

    
    @Inject(method = "renderVignetteOverlay", at = @At("HEAD"), require = 0)
    private void metalrender$beforeVignette(DrawContext context, Entity entity, CallbackInfo ci) {
        if (!MetalRenderClient.isEnabled())
            return;

        metalrender$frameCount++;
        MetalWorldRenderer renderer = MetalRenderClient.getWorldRenderer();
        if (renderer != null && renderer.hasRenderedThisFrame()) {
            renderer.forceBlitNow();
        }

        if (metalrender$frameCount <= 10 || metalrender$frameCount % 600 == 0) {
            MetalLogger.info("[StatusEffectOverlayMixin] Vignette overlay: Metal composited before overlays (frame %d)",
                    metalrender$frameCount);
        }
    }

    
    @Inject(method = "renderPortalOverlay", at = @At("HEAD"), require = 0)
    private void metalrender$beforePortalOverlay(DrawContext context, float nauseaIntensity, CallbackInfo ci) {
        if (!MetalRenderClient.isEnabled())
            return;

        MetalWorldRenderer renderer = MetalRenderClient.getWorldRenderer();
        if (renderer != null && renderer.hasRenderedThisFrame()) {
            renderer.forceBlitNow();
        }
    }

    
    @Inject(method = "renderNauseaOverlay", at = @At("HEAD"), require = 0)
    private void metalrender$beforeNauseaOverlay(DrawContext context, float nauseaIntensity, CallbackInfo ci) {
        if (!MetalRenderClient.isEnabled())
            return;

        MetalWorldRenderer renderer = MetalRenderClient.getWorldRenderer();
        if (renderer != null && renderer.hasRenderedThisFrame()) {
            renderer.forceBlitNow();
        }
    }
}
