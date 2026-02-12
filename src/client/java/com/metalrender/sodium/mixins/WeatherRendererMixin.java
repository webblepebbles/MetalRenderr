package com.metalrender.sodium.mixins;

import com.metalrender.MetalRenderClient;
import com.metalrender.entity.EntityCaptureState;
import com.metalrender.entity.MetalEntityRenderer;
import com.metalrender.render.MetalWorldRenderer;
import com.metalrender.render.unified.MetalRenderCoordinator;
import com.metalrender.util.MetalLogger;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WeatherRendering;
import net.minecraft.client.render.state.WeatherRenderState;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WeatherRendering.class)
public class WeatherRendererMixin {

    @Unique
    private static int metalrender$frameCount = 0;

    
    @Inject(method = "renderPrecipitation", at = @At("HEAD"), require = 0)
    private void metalrender$beforeRenderPrecipitation(
            VertexConsumerProvider vertexConsumerProvider,
            Vec3d cameraPos,
            WeatherRenderState weatherState,
            CallbackInfo ci) {
        if (!MetalRenderClient.isEnabled())
            return;

        metalrender$frameCount++;
        MetalWorldRenderer renderer = MetalRenderClient.getWorldRenderer();
        if (renderer != null && renderer.hasRenderedThisFrame()) {
            renderer.forceBlitNow();
        }
        EntityCaptureState.enableWeatherCapture();

        if (metalrender$frameCount <= 10 || metalrender$frameCount % 300 == 0) {
            MetalLogger.info("[WeatherRendererMixin] Enabling weather capture for Metal (frame %d)",
                    metalrender$frameCount);
        }
    }

    
    @Inject(method = "renderPrecipitation", at = @At("RETURN"), require = 0)
    private void metalrender$afterRenderPrecipitation(
            VertexConsumerProvider vertexConsumerProvider,
            Vec3d cameraPos,
            WeatherRenderState weatherState,
            CallbackInfo ci) {
        if (!MetalRenderClient.isEnabled())
            return;
        EntityCaptureState.disableWeatherCapture();
        MetalRenderCoordinator coordinator = MetalRenderCoordinator.getInstance();
        if (coordinator != null && coordinator.isInitialized()) {
            coordinator.renderWeather();

            if (metalrender$frameCount <= 10 || metalrender$frameCount % 300 == 0) {
                MetalLogger.info("[WeatherRendererMixin] Rendered weather via Metal (frame %d)",
                        metalrender$frameCount);
            }
        }
    }
}
