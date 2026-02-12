package com.metalrender.sodium.mixins;

import com.metalrender.config.MetalRenderConfig;
import com.metalrender.lod.LodChunkIngestor;
import com.metalrender.util.MetalLogger;
import net.minecraft.client.world.ClientChunkManager;
import net.minecraft.util.math.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientChunkManager.class)
public class ClientChunkManagerMixin {

    @Inject(method = "unload", at = @At("HEAD"))
    private void metalrender$captureBeforeUnload(ChunkPos pos, CallbackInfo ci) {
        if (!MetalRenderConfig.extendedLodEnabled() || !MetalRenderConfig.extendedLodIngestEnabled()) {
            return;
        }

        LodChunkIngestor ingestor = LodChunkIngestor.getInstance();
        if (!ingestor.isRunning()) {
            return;
        }

        try {
            ingestor.captureChunkAt(pos.x, pos.z);
        } catch (Exception e) {
            MetalLogger.warn("[ClientChunkManagerMixin] Failed to capture chunk ({}, {}) before unload: {}",
                    pos.x, pos.z, e.getMessage());
        }
    }
}
