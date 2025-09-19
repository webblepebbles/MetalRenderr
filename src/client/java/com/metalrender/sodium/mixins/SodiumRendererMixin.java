package com.metalrender.sodium.mixins;

import com.metalrender.sodium.backend.MetalRendererBackend;
import com.metalrender.sodium.backend.MeshShaderBackend;
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
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import java.nio.ByteBuffer;

@Environment(EnvType.CLIENT)
@Mixin(SodiumWorldRenderer.class)
public class SodiumRendererMixin {
    @Shadow private MinecraftClient client;
    private MetalRendererBackend metalBackend;
    @Inject(method = "<init>", at = @At("RETURN"))
    private void onConstruct(MinecraftClient client, CallbackInfo ci) {
        try {
            this.metalBackend = new MetalRendererBackend(client);
        } catch (Throwable t) {
            this.metalBackend = null;
        }
    }
    @Inject(method = "setLevel", at = @At("HEAD"))
    private void onSetLevel(ClientWorld level, CallbackInfo ci) {
        if (metalBackend != null) {
            metalBackend.initIfNeeded();
        }
    }
    @Inject(method = "setupTerrain", at = @At("HEAD"))
    private void onSetupTerrain(Camera camera, Viewport viewport, FogParameters fogParameters,
                                boolean spectator, boolean updateChunksImmediately, ChunkRenderMatrices matrices, CallbackInfo ci) {
        if (metalBackend != null && client != null) {
            float fov = (client.options != null && client.options.getFov() != null) ? (float) client.options.getFov().getValue() : 70f;
            metalBackend.onSetupTerrain(fov);
        }
    }

    @Inject(method = "drawChunkLayer", at = @At("HEAD"), cancellable = true)
    private void onDrawChunkLayer(RenderLayer group, ChunkRenderMatrices matrices, double x, double y, double z, CallbackInfoReturnable<Boolean> cir) {
        if (metalBackend != null) {
            int layer = 0; 
            boolean handled = metalBackend.drawChunkLayerSodiumOverride(layer);
            if (handled) cir.setReturnValue(false);
        }
        MeshShaderBackend meshBackend = com.metalrender.MetalRenderClient.getMeshBackend();
        if (meshBackend != null && meshBackend.isMeshEnabled()) {
            meshBackend.processDrawQueue();
        }
    }


    public void queueMeshUpload(BlockPos pos, ByteBuffer vertexData, int vertexCount, int vertexStride,
                                ByteBuffer indexData, int indexCount, int indexType) {
        MeshShaderBackend meshBackend = com.metalrender.MetalRenderClient.getMeshBackend();
        if (meshBackend != null && meshBackend.isMeshEnabled()) {
            meshBackend.uploadChunkMeshAsync(pos, vertexData, vertexCount, vertexStride, indexData, indexCount, indexType);
        }
    }
    
}