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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.util.math.BlockPos;
import java.nio.ByteBuffer;
import net.minecraft.client.render.BlockRenderLayerGroup;


@Environment(EnvType.CLIENT)
@Mixin(SodiumWorldRenderer.class)
public class SodiumRendererMixin {
    @Shadow private MinecraftClient client;
    private volatile boolean meshFrameEnded = false;
    // Use global backends from MetalRenderClient; do not construct another backend per renderer.
    @Inject(method = "setupTerrain", at = @At("HEAD"))
    private void onSetupTerrain(Camera camera, Viewport viewport, FogParameters fogParameters,
                                boolean spectator, boolean updateChunksImmediately, ChunkRenderMatrices matrices, CallbackInfo ci) {
        // Drive rendering for the active backend
        float fov = (client != null && client.options != null && client.options.getFov() != null) ? (float) client.options.getFov().getValue() : 70f;
        MetalRendererBackend fb = com.metalrender.MetalRenderClient.getFallbackBackend();
        if (fb != null && !com.metalrender.MetalRenderClient.isUsingMeshShaders()) {
            fb.onSetupTerrain(fov);
        }
       
        meshFrameEnded = false;
        MeshShaderBackend meshBackend = com.metalrender.MetalRenderClient.getMeshBackend();
        if (meshBackend != null && meshBackend.isMeshEnabled()) {
            try { meshBackend.beginFrame(); } catch (Throwable ignored) {}
        }
    }

    @Inject(method = "drawChunkLayer", at = @At("HEAD"), cancellable = true)
    private void onDrawChunkLayer(BlockRenderLayerGroup group,
                                  ChunkRenderMatrices matrices,
                                  double x, double y, double z,
                                  CallbackInfo ci) {
        // Allow Sodium to render its normal chunk layers; do not cancel.
        MeshShaderBackend meshBackend = com.metalrender.MetalRenderClient.getMeshBackend();
        if (meshBackend != null && meshBackend.isMeshEnabled()) {
            try {
                meshBackend.processDrawQueue();
            } catch (Throwable ignored) {}
            if (!meshFrameEnded) {
                meshFrameEnded = true;
                try { meshBackend.endFrame(); } catch (Throwable ignored) {}
            }
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
