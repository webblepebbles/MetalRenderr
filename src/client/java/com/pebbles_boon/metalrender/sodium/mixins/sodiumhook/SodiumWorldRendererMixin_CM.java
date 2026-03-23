package com.pebbles_boon.metalrender.sodium.mixins.sodiumhook;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.minecraft.client.gl.GpuSampler;
import net.minecraft.client.render.BlockRenderLayerGroup;
import net.minecraft.client.render.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer")
public class SodiumWorldRendererMixin_CM {
        @Inject(method = "setupTerrain", at = @At("HEAD"), require = 0)
        private void metalrender$setupTerrain(Camera camera, Viewport viewport,
                        FogParameters fogParams, boolean isSpectator,
                        boolean captureFrustum, ChunkRenderMatrices matrices,
                        CallbackInfo ci) {
        }
        @Inject(method = "drawChunkLayer", at = @At("HEAD"), cancellable = true, require = 0)
        private void metalrender$drawChunkLayer(BlockRenderLayerGroup terrainPass,
                        ChunkRenderMatrices matrices, double x, double y,
                        double z, GpuSampler sampler, CallbackInfo ci) {
        }
}
