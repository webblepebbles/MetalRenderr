package com.pebbles_boon.metalrender.sodium.mixins;

import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.particle.MetalParticleRenderer;
import com.pebbles_boon.metalrender.render.MetalWorldRenderer;
import com.pebbles_boon.metalrender.sodium.mixins.accessor.ParticleManagerAccessor;
import com.pebbles_boon.metalrender.util.MetalLogger;
import java.util.Map;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.particle.ParticleManager;
import net.minecraft.client.particle.ParticleRenderer;
import net.minecraft.client.particle.ParticleTextureSheet;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.SubmittableBatch;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


@Mixin(ParticleManager.class)
public class ParticleCaptureMixin {

  @Unique private int metalrender$captureFrameCount = 0;


  @Inject(method = "addToBatch", at = @At("HEAD"), cancellable = true,
          require = 0)
  private void
  metalrender$captureAndCancelParticles(SubmittableBatch batch, Frustum frustum,
                                        Camera camera, float tickDelta,
                                        CallbackInfo ci) {

    if (!MetalRenderClient.isEnabled())
      return;

    MetalWorldRenderer worldRenderer = MetalRenderClient.getWorldRenderer();
    if (worldRenderer == null || !worldRenderer.shouldRenderWithMetal())
      return;

    MetalParticleRenderer particleRenderer =
        worldRenderer.getParticleRenderer();
    if (particleRenderer == null || !particleRenderer.isActive())
      return;


    particleRenderer.captureParticles((ParticleManager)(Object)this, camera,
                                      tickDelta);


    try {
      ParticleManagerAccessor accessor = (ParticleManagerAccessor)(Object)this;
      Map<ParticleTextureSheet, ParticleRenderer<?>> particlesMap =
          accessor.metalrender$getParticles();

      if (particlesMap != null) {
        for (Map.Entry<ParticleTextureSheet, ParticleRenderer<?>> entry :
             particlesMap.entrySet()) {
          ParticleRenderer<?> renderer = entry.getValue();
          if (renderer != null && !renderer.isEmpty()) {
            particleRenderer.captureParticleList(renderer.getParticles(),
                                                 camera, tickDelta);
          }
        }
      }
    } catch (Exception e) {
      if (metalrender$captureFrameCount < 5) {
        MetalLogger.error("[ParticleCaptureMixin] Failed to capture: %s",
                          e.getMessage());
        e.printStackTrace();
      }
    }

    metalrender$captureFrameCount++;
    if (metalrender$captureFrameCount <= 3 ||
        metalrender$captureFrameCount % 500 == 0) {
      MetalLogger.info(
          "[ParticleCaptureMixin] Captured particles frame %d, cancelling GL",
          metalrender$captureFrameCount);
    }


    ci.cancel();
  }
}
