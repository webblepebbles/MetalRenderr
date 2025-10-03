package com.metalrender.sodium.mixins;

import com.metalrender.sodium.backend.MetalRendererBackend;
import com.metalrender.sodium.backend.MeshShaderBackend;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
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

@Mixin(SodiumWorldRenderer.class)
public class SodiumRendererMixin {
    @Shadow
    private MinecraftClient client;
    private volatile boolean meshFrameEnded = false;

    @Inject(method = "setupTerrain", at = @At("HEAD"))
    private void onSetupTerrain(Camera camera, Viewport viewport, FogParameters fogParameters,
            boolean spectator, boolean updateChunksImmediately, ChunkRenderMatrices matrices, CallbackInfo ci) {

        float fov = (client != null && client.options != null && client.options.getFov() != null)
                ? (float) client.options.getFov().getValue()
                : 70f;

        MetalRendererBackend fb = com.metalrender.MetalRenderClient.getFallbackBackend();
        if (fb != null) {
            fb.onSetupTerrain(fov);
        }

        MeshShaderBackend meshBackend = com.metalrender.MetalRenderClient.getMeshBackend();
        if (meshBackend != null && meshBackend.isMeshEnabled()) {
            meshFrameEnded = false;
            try {
                meshBackend.beginFrame();
            } catch (UnsatisfiedLinkError | IllegalArgumentException e) {
                org.slf4j.LoggerFactory.getLogger("SodiumRendererMixin").error("Error in beginFrame:", e);
            }
        }
    }

    @Inject(method = "drawChunkLayer", at = @At("HEAD"))
    private void onDrawChunkLayer(BlockRenderLayerGroup group,
            ChunkRenderMatrices matrices,
            double x, double y, double z,
            CallbackInfo ci) {

        MeshShaderBackend meshBackend = com.metalrender.MetalRenderClient.getMeshBackend();
        if (meshBackend != null && meshBackend.isMeshEnabled()) {
            try {
                meshBackend.processDrawQueue();
            } catch (UnsatisfiedLinkError | IllegalArgumentException e) {
                org.slf4j.LoggerFactory.getLogger("SodiumRendererMixin").error("Error in processDrawQueue:", e);
            }
            if (!meshFrameEnded) {
                meshFrameEnded = true;
                try {
                    meshBackend.endFrame();
                } catch (UnsatisfiedLinkError | IllegalArgumentException e) {
                    org.slf4j.LoggerFactory.getLogger("SodiumRendererMixin").error("Error in endFrame:", e);
                }
            }
        }
    }

    public void queueMeshUpload(BlockPos pos, ByteBuffer vertexData, int vertexCount, int vertexStride,
            ByteBuffer indexData, int indexCount, int indexType) {
        MeshShaderBackend meshBackend = com.metalrender.MetalRenderClient.getMeshBackend();
        if (meshBackend != null && meshBackend.isMeshEnabled()) {
            meshBackend.uploadChunkMeshAsync(pos, vertexData, vertexCount, vertexStride, indexData, indexCount,
                    indexType);
        }
    }

}
