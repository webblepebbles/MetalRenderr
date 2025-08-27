package com.metalrender.sodium.mixin;

import com.metalrender.sodium.MetalRendererBackend;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.class_11531;
import net.minecraft.class_4184;
import net.minecraft.class_310;

@Environment(EnvType.CLIENT)
@Mixin(SodiumWorldRenderer.class)
public class SodiumRendererMixin {
    @Shadow private class_310 client;
    private MetalRendererBackend metalBackend;
    @Inject(method = "<init>", at = @At("RETURN"))
    private void onConstruct(class_310 client, CallbackInfo ci) {
        try {
            this.metalBackend = new MetalRendererBackend(client);
        } catch (Throwable t) {
            this.metalBackend = null;
        }
    }
    @Inject(method = "setLevel", at = @At("HEAD"))
    private void onSetLevel(net.minecraft.class_638 level, CallbackInfo ci) {
        if (metalBackend != null) {
            metalBackend.initIfNeeded();
            if (level != null) metalBackend.initIfNeeded();
        }
    }
    @Inject(method = "setupTerrain", at = @At("HEAD"))
    private void onSetupTerrain(class_4184 camera, Viewport viewport, FogParameters fogParameters,
                                boolean spectator, boolean updateChunksImmediately, ChunkRenderMatrices matrices, CallbackInfo ci) {
        if (metalBackend != null) {
            float fov = client.field_1724 != null ? client.field_1724.method_5755() : 70f;
            metalBackend.onSetupTerrain(fov);
        }
    }
    @Inject(method = "drawChunkLayer", at = @At("HEAD"), cancellable = true)
    private void onDrawChunkLayer(class_11531 group, ChunkRenderMatrices matrices, double x, double y, double z, CallbackInfoReturnable<Boolean> cir) {
        if (metalBackend != null) {
            int layer = (group == class_11531.field_61023) ? 1 : 0;
            boolean handled = metalBackend.drawChunkLayerSodiumOverride(layer);
            if (handled) cir.setReturnValue(false);
        }
    }
    @Inject(method = "reload", at = @At("HEAD"))
    private void onReload(CallbackInfo ci) {
        if (metalBackend != null) metalBackend.initIfNeeded();
    }

    @Inject(method = "unloadLevel", at = @At("HEAD"))
    private void onUnloadLevel(CallbackInfo ci) {
        if (metalBackend != null) metalBackend.destroy();
    }
}