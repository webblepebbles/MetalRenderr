package com.metalrender.sodium.mixins;

import com.metalrender.config.MetalRenderConfig;
import com.metalrender.lod.LodChunkIngestor;
import com.metalrender.util.MetalLogger;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public abstract class ChunkDataMixin {

    @Shadow
    private ClientWorld world;

    @Inject(method = "onChunkData", at = @At("TAIL"), require = 0)
    private void metalrender$onChunkDataReceived(ChunkDataS2CPacket packet, CallbackInfo ci) {
        if (!MetalRenderConfig.extendedLodEnabled() || !MetalRenderConfig.extendedLodIngestEnabled())
            return;

        try {
            LodChunkIngestor ingestor = LodChunkIngestor.getInstance();
            if (!ingestor.isRunning())
                return;

            int chunkX = packet.getChunkX();
            int chunkZ = packet.getChunkZ();

            if (world != null) {
                WorldChunk chunk = world.getChunk(chunkX, chunkZ);
                if (chunk != null) {
                    ingestor.captureChunk(chunk);
                }
            }
        } catch (Exception e) {
        }
    }
}
