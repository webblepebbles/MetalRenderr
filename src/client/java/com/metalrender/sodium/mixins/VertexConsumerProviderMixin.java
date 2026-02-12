package com.metalrender.sodium.mixins;

import com.metalrender.MetalRenderClient;
import com.metalrender.entity.EntityCaptureState;
import com.metalrender.entity.MetalEntityRenderer;
import com.metalrender.entity.MetalVertexConsumer;
import com.metalrender.util.MetalLogger;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Mixin(VertexConsumerProvider.Immediate.class)
public abstract class VertexConsumerProviderMixin {
    private static final Pattern TEXTURE_PATTERN = Pattern.compile("location=([^,\\]]+)");

    private static String extractTextureId(String layerString) {
        Matcher matcher = TEXTURE_PATTERN.matcher(layerString);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    
    @Inject(method = "getBuffer", at = @At("HEAD"), cancellable = true)
    private void metalrender$interceptGetBuffer(RenderLayer layer, CallbackInfoReturnable<VertexConsumer> cir) {
        if (!MetalRenderClient.isEnabled()) {
            return;
        }

        if (!EntityCaptureState.isEnabled()) {
            return;
        }

        String layerName = layer.toString();
        if (layerName.contains("glint") || layerName.contains("Glint")) {
            return;
        }
        boolean isWeatherLayer = layerName.contains("weather") || layerName.contains("WEATHER");
        if (isWeatherLayer) {
            if (!EntityCaptureState.isWeatherCaptureEnabled()) {
                return; 
            }
            MetalEntityRenderer entityRenderer = MetalEntityRenderer.getInstance();
            if (entityRenderer != null && entityRenderer.isEnabled()) {
                MetalVertexConsumer consumer = entityRenderer.getVertexConsumer();
                String textureId = extractTextureId(layerName);
                if (textureId != null && textureId.contains(":")) {
                    consumer.setTexture(textureId);
                }
                consumer.setLineMode(false);
                cir.setReturnValue(consumer);
            }
            return;
        }
        boolean isEntityLayer = layerName.contains("entity_cutout") ||
                layerName.contains("entity_solid") ||
                layerName.contains("entity_translucent") ||
                layerName.contains("entity_no_outline") ||
                layerName.contains("armor_cutout") ||
                layerName.contains("eyes") ||
                layerName.contains("outer") ||
                layerName.contains("player");
        boolean isLineLayer = layerName.contains("lines") ||
                layerName.contains("debug_line") ||
                layerName.contains("LINE");
        boolean isTextLayer = layerName.contains("text_intensity") ||
                layerName.contains("text_see_through") ||
                layerName.contains("text_polygon_offset") ||
                layerName.contains("TEXT_INTENSITY") ||
                layerName.contains("text:RenderSetup");
        if (isEntityLayer || isTextLayer || isLineLayer) {
            MetalEntityRenderer entityRenderer = MetalEntityRenderer.getInstance();
            if (!entityRenderer.isEnabled()) {
                return;
            }

            MetalVertexConsumer consumer = entityRenderer.getVertexConsumer();
            if (isEntityLayer) {
                String textureId = extractTextureId(layerName);
                if (textureId != null && textureId.contains(":")) {
                    consumer.setTexture(textureId);
                }
            } else {
                String textureId = extractTextureId(layerName);
                if (textureId != null && textureId.contains(":")) {
                    consumer.setTexture(textureId);
                } else {
                    consumer.setTexture(null);
                }
            }
            consumer.setLineMode(isLineLayer);

            cir.setReturnValue(consumer);
        }
    }
}
