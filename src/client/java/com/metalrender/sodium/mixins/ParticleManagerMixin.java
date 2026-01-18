package com.metalrender.sodium.mixins;

import com.metalrender.MetalRenderClient;
import com.metalrender.render.particle.MetalParticleRenderer;
import com.metalrender.render.unified.MetalRenderCoordinator;
import net.minecraft.client.particle.ParticleManager;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.VertexConsumerProvider;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


@Mixin(ParticleManager.class)
public abstract class ParticleManagerMixin {

    private static int frameCount = 0;

    @Inject(method = "renderParticles", at = @At("HEAD"), cancellable = true, require = 0)
    private void metalrender$beforeRenderParticles(
            LightmapTextureManager lightmapTextureManager,
            Camera camera,
            float tickDelta,
            CallbackInfo ci) {

        if (!MetalRenderClient.isEnabled())
            return;

        frameCount++;

        MetalParticleRenderer particleRenderer = MetalRenderCoordinator.getInstance().getParticleRenderer();

        net.minecraft.util.math.Vec3d cameraPos = camera.getCameraPos();
        float camX = (float) cameraPos.x;
        float camY = (float) cameraPos.y;
        float camZ = (float) cameraPos.z;

        Matrix4f viewMatrix = new Matrix4f().identity();
        float pitch = (float) Math.toRadians(camera.getPitch());
        float yaw = (float) Math.toRadians(camera.getYaw() + 180.0f);
        viewMatrix.rotateY(-yaw);
        viewMatrix.rotateX(-pitch);
        viewMatrix.translate(-camX, -camY, -camZ);

        Matrix4f projection = new Matrix4f().setPerspective(
                (float) Math.toRadians(70.0),
                16.0f / 9.0f,
                0.05f,
                512.0f);

        Matrix4f viewProj = new Matrix4f();
        projection.mul(viewMatrix, viewProj);
        if (particleRenderer != null && particleRenderer.isEnabled()) {
            particleRenderer.beginFrame(viewProj, viewMatrix, camX, camY, camZ);
        }
        ci.cancel();
    }
    @Inject(method = "renderParticles", at = @At("RETURN"), require = 0)
    private void metalrender$afterRenderParticles(
            LightmapTextureManager lightmapTextureManager,
            Camera camera,
            float tickDelta,
            CallbackInfo ci) {

        if (!MetalRenderClient.isEnabled())
            return;

        MetalParticleRenderer particleRenderer = MetalRenderCoordinator.getInstance().getParticleRenderer();
        if (particleRenderer == null || !particleRenderer.isEnabled())
            return;

        particleRenderer.endFrame();
    }
}
