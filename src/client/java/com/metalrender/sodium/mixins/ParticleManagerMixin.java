package com.metalrender.sodium.mixins;

import com.metalrender.MetalRenderClient;
import com.metalrender.util.MetalLogger;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import net.minecraft.client.render.FrameGraphBuilder;
import net.minecraft.client.render.WorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WorldRenderer.class)
public abstract class ParticleManagerMixin {

    @Unique
    private static int metalrender$particleFrameCount = 0;

    
    @Inject(method = "renderParticles", at = @At("HEAD"), require = 0)
    private void metalrender$beforeRenderParticles(
            FrameGraphBuilder frameGraphBuilder,
            GpuBufferSlice fogBuffer,
            CallbackInfo ci) {
        if (!MetalRenderClient.isEnabled())
            return;

        metalrender$particleFrameCount++;

        if (metalrender$particleFrameCount <= 10 || metalrender$particleFrameCount % 300 == 0) {
            MetalLogger.info("[ParticleManagerMixin] Particle frame graph pass scheduled (frame %d)",
                    metalrender$particleFrameCount);
        }
    }
}
