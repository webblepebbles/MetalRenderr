package com.metalrender.sodium.mixins;

import com.metalrender.MetalRenderClient;
import com.metalrender.entity.EntityCaptureState;
import com.metalrender.entity.MetalEntityRenderer;
import com.metalrender.entity.MetalVertexConsumer;
import com.metalrender.render.particle.ParticleCaptureState;
import com.metalrender.util.MetalLogger;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.particle.BillboardParticle;
import net.minecraft.client.particle.BillboardParticleSubmittable;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BillboardParticleSubmittable.class)
public class BillboardParticleSubmittableMixin {

    static {
        System.out.println("[BillboardParticleSubmittableMixin] *** STATIC INIT - MIXIN LOADED ***");
    }

    @Unique
    private static int metalrender$particleCount = 0;
    @Unique
    private static int metalrender$logCount = 0;
    @Unique
    private static String metalrender$lastTexture = null;

    @Unique
    private static final Vector3f metalrender$corner = new Vector3f();
    @Unique
    private static final Quaternionf metalrender$quat = new Quaternionf();

    @Inject(method = "render(Lnet/minecraft/client/particle/BillboardParticle$RenderType;FFFFFFFFFFFFII)V", at = @At("HEAD"), require = 1)
    private void metalrender$onRender(
            BillboardParticle.RenderType renderType,
            float x, float y, float z,
            float rotX, float rotY, float rotZ, float rotW,
            float size,
            float minU, float maxU, float minV, float maxV,
            int color, int brightness,
            CallbackInfo ci) {

        if (!MetalRenderClient.isEnabled())
            return;
        if (!EntityCaptureState.isEnabled())
            return;

        MetalEntityRenderer entityRenderer = MetalEntityRenderer.getInstance();
        if (entityRenderer == null || !entityRenderer.isEnabled())
            return;

        MetalVertexConsumer metalConsumer = entityRenderer.getVertexConsumer();
        if (metalConsumer == null)
            return;

        String texture = metalrender$getTextureFromRenderType(renderType);

        if (texture.contains("blocks")) {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc != null && mc.player != null) {
                ClientPlayerEntity player = mc.player;

                double horizDist = (double) x * x + (double) z * z;
                boolean nearPlayer = horizDist < 4.0 && y > -2.5f && y < 0.5f;

                if (nearPlayer) {

                    if (!player.isOnGround() || player.isSubmergedInWater()
                            || player.isSwimming() || player.isSpectator()) {
                        return;
                    }
                }
            }
        }

        if (!texture.equals(metalrender$lastTexture)) {
            metalConsumer.setTexture(texture);
            metalrender$lastTexture = texture;
        }

        metalrender$quat.set(rotX, rotY, rotZ, rotW);

        metalrender$corner.set(1.0f, -1.0f, 0.0f);
        metalrender$corner.rotate(metalrender$quat);
        metalrender$corner.mul(size);
        metalrender$corner.add(x, y, z);
        metalConsumer.vertex(metalrender$corner.x, metalrender$corner.y, metalrender$corner.z)
                .texture(maxU, maxV)
                .color(color)
                .light(brightness);

        metalrender$corner.set(1.0f, 1.0f, 0.0f);
        metalrender$corner.rotate(metalrender$quat);
        metalrender$corner.mul(size);
        metalrender$corner.add(x, y, z);
        metalConsumer.vertex(metalrender$corner.x, metalrender$corner.y, metalrender$corner.z)
                .texture(maxU, minV)
                .color(color)
                .light(brightness);

        metalrender$corner.set(-1.0f, 1.0f, 0.0f);
        metalrender$corner.rotate(metalrender$quat);
        metalrender$corner.mul(size);
        metalrender$corner.add(x, y, z);
        metalConsumer.vertex(metalrender$corner.x, metalrender$corner.y, metalrender$corner.z)
                .texture(minU, minV)
                .color(color)
                .light(brightness);

        metalrender$corner.set(-1.0f, -1.0f, 0.0f);
        metalrender$corner.rotate(metalrender$quat);
        metalrender$corner.mul(size);
        metalrender$corner.add(x, y, z);
        metalConsumer.vertex(metalrender$corner.x, metalrender$corner.y, metalrender$corner.z)
                .texture(minU, maxV)
                .color(color)
                .light(brightness);

        metalrender$particleCount++;
        ParticleCaptureState.incrementParticleCount();

        if (metalrender$particleCount <= 10 || metalrender$particleCount % 5000 == 0) {
            metalrender$logCount++;
            if (metalrender$logCount <= 20 || metalrender$logCount % 100 == 0) {
                MetalLogger.info(
                        "[BillboardParticleSubmittableMixin] Captured particle #{} tex={} pos=({},{},{}) size={}",
                        metalrender$particleCount, texture, x, y, z, size);
            }
        }
    }

    @Unique
    private static String metalrender$getTextureFromRenderType(BillboardParticle.RenderType renderType) {

        String str = renderType.toString();
        int idx = str.indexOf("textureAtlasLocation=");
        if (idx >= 0) {
            int start = idx + "textureAtlasLocation=".length();
            int end = str.indexOf(',', start);
            if (end < 0)
                end = str.indexOf(']', start);
            if (end < 0)
                end = str.length();
            return str.substring(start, end).trim();
        }

        return "minecraft:textures/atlas/particles.png";
    }
}
