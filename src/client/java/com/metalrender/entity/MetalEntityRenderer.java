package com.metalrender.entity;

import com.metalrender.nativebridge.EntityBatchBuilder;
import com.metalrender.nativebridge.NativeBridge;
import com.metalrender.util.MetalLogger;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.util.Identifier;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

public class MetalEntityRenderer {

    private static final MetalEntityRenderer INSTANCE = new MetalEntityRenderer();

    private final MetalVertexConsumer vertexConsumer = new MetalVertexConsumer();

    private final EntityTextureCache textureCache = EntityTextureCache.getInstance();

    private final List<MetalVertexConsumer.CapturedMesh> pendingMeshes = new ArrayList<>();

    private long deviceHandle = 0;
    private final EntityBatchBuilder entityBatch = new EntityBatchBuilder();

    private volatile boolean enabled = false;

    private float[] viewProjection = new float[16];
    private float[] cameraPos = new float[4];

    private float[] overrideProjection = null;

    private int entitiesRenderedThisFrame = 0;
    private int verticesRenderedThisFrame = 0;

    private MetalEntityRenderer() {
    }

    public static MetalEntityRenderer getInstance() {
        return INSTANCE;
    }

    public void initialize(long device) {
        this.deviceHandle = device;
        this.textureCache.initialize(device);
        this.enabled = true;
        MetalLogger.info("[MetalEntityRenderer] Initialized with device handle: {}", device);
    }

    public boolean isEnabled() {
        return enabled && deviceHandle != 0;
    }

    public void beginFrame(Camera camera, Matrix4f viewProj) {
        if (!enabled)
            return;
        vertexConsumer.beginFrame();
        pendingMeshes.clear();
        entitiesRenderedThisFrame = 0;
        verticesRenderedThisFrame = 0;
        viewProj.get(viewProjection);

        cameraPos[0] = 0.0f;
        cameraPos[1] = 0.0f;
        cameraPos[2] = 0.0f;
        cameraPos[3] = 1.0f;
    }

    public MetalVertexConsumer getVertexConsumer() {
        return vertexConsumer;
    }

    public void setEntityTransform(Matrix4f positionMatrix, Matrix3f normalMatrix) {
        vertexConsumer.setTransforms(positionMatrix, normalMatrix);
    }

    public void setEntityTexture(Identifier texture) {
        vertexConsumer.setTexture(texture.toString());
    }

    public void finishEntity() {
        vertexConsumer.flushMesh();
        entitiesRenderedThisFrame++;
    }

    public void endCapture() {
        List<MetalVertexConsumer.CapturedMesh> meshes = vertexConsumer.harvest();
        pendingMeshes.addAll(meshes);

        for (MetalVertexConsumer.CapturedMesh mesh : meshes) {
            verticesRenderedThisFrame += mesh.vertexCount;
        }
    }

    public void renderEntities() {
        if (!enabled || deviceHandle == 0) {
            return;
        }
        updateLightParams();

        if (pendingMeshes.isEmpty()) {
            try {
                NativeBridge.nBeginEntityPass(deviceHandle, viewProjection, cameraPos);
                NativeBridge.nEndEntityPass(deviceHandle);
            } catch (Throwable e) {
            }
            return;
        }

        try {
            float[] projectionToUse = (overrideProjection != null) ? overrideProjection : viewProjection;
            NativeBridge.nBeginEntityPass(deviceHandle, projectionToUse, cameraPos);

            entityBatch.begin();
            for (MetalVertexConsumer.CapturedMesh mesh : pendingMeshes) {
                if (mesh.vertexCount <= 0)
                    continue;

                long textureHandle = 0;
                if (mesh.textureId != null) {
                    textureHandle = textureCache.getOrCreateTexture(mesh.textureId);
                }

                entityBatch.addMesh(mesh.vertexData, mesh.vertexCount, textureHandle);
            }
            entityBatch.submit(deviceHandle);

            NativeBridge.nEndEntityPass(deviceHandle);
        } catch (Throwable e) {
            System.err.println("[MetalEntityRenderer] ERROR: " + e.getMessage());
        }

        pendingMeshes.clear();
    }

    public int getEntitiesRenderedThisFrame() {
        return entitiesRenderedThisFrame;
    }

    public int getVerticesRenderedThisFrame() {
        return verticesRenderedThisFrame;
    }

    public void setOverrideProjection(Matrix4f projection) {
        if (projection != null) {
            if (overrideProjection == null) {
                overrideProjection = new float[16];
            }
            projection.get(overrideProjection);
        } else {
            overrideProjection = null;
        }
    }

    private void updateLightParams() {
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc == null || mc.world == null) {
                NativeBridge.nSetLightParams(deviceHandle, 1.0f, 0.1f, 0.0f);
                return;
            }
            ClientWorld world = mc.world;
            float tickDelta = mc.getRenderTickCounter().getTickProgress(true);
            float dayBrightness = world.getEnvironmentAttributes()
                    .getAttributeValue(EnvironmentAttributes.SKY_LIGHT_FACTOR_VISUAL);
            boolean hasNightVision = false;
            if (mc.player != null) {
                hasNightVision = mc.player.hasStatusEffect(StatusEffects.NIGHT_VISION);
            }
            if (hasNightVision) {
                dayBrightness = 1.0f;
            }
            float ambientLight = Math.max(0.15f, dayBrightness * 0.35f);
            if (hasNightVision) {
                ambientLight = 0.7f;
            }
            float skyAngle = world.getEnvironmentAttributes().getAttributeValue(EnvironmentAttributes.SUN_ANGLE_VISUAL)
                    / 360.0f;

            NativeBridge.nSetLightParams(deviceHandle, dayBrightness, ambientLight, skyAngle);
        } catch (Throwable e) {
            NativeBridge.nSetLightParams(deviceHandle, 1.0f, 0.1f, 0.0f);
        }
    }

    public void destroy() {
        enabled = false;
        textureCache.clear();
        vertexConsumer.beginFrame();
        pendingMeshes.clear();
        MetalLogger.info("[MetalEntityRenderer] Destroyed");
    }
}
