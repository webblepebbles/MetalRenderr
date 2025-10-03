package com.metalrender.util;

import net.minecraft.client.render.Camera;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Vector4f;

public class FrustumCuller {
    private final Vector4f[] frustumPlanes = new Vector4f[6];
    private boolean frustumValid = false;

    public FrustumCuller() {
        for (int i = 0; i < 6; i++) {
            frustumPlanes[i] = new Vector4f();
        }
    }

    public void updateFrustum(Matrix4f viewProjectionMatrix) {
        frustumPlanes[0].set(
            viewProjectionMatrix.m30() + viewProjectionMatrix.m00(),
            viewProjectionMatrix.m31() + viewProjectionMatrix.m01(),
            viewProjectionMatrix.m32() + viewProjectionMatrix.m02(),
            viewProjectionMatrix.m33() + viewProjectionMatrix.m03()
        );

        frustumPlanes[1].set(
            viewProjectionMatrix.m30() - viewProjectionMatrix.m00(),
            viewProjectionMatrix.m31() - viewProjectionMatrix.m01(),
            viewProjectionMatrix.m32() - viewProjectionMatrix.m02(),
            viewProjectionMatrix.m33() - viewProjectionMatrix.m03()
        );

        frustumPlanes[2].set(
            viewProjectionMatrix.m30() - viewProjectionMatrix.m10(),
            viewProjectionMatrix.m31() - viewProjectionMatrix.m11(),
            viewProjectionMatrix.m32() - viewProjectionMatrix.m12(),
            viewProjectionMatrix.m33() - viewProjectionMatrix.m13()
        );

        frustumPlanes[3].set(
            viewProjectionMatrix.m30() + viewProjectionMatrix.m10(),
            viewProjectionMatrix.m31() + viewProjectionMatrix.m11(),
            viewProjectionMatrix.m32() + viewProjectionMatrix.m12(),
            viewProjectionMatrix.m33() + viewProjectionMatrix.m13()
        );

        frustumPlanes[4].set(
            viewProjectionMatrix.m30() + viewProjectionMatrix.m20(),
            viewProjectionMatrix.m31() + viewProjectionMatrix.m21(),
            viewProjectionMatrix.m32() + viewProjectionMatrix.m22(),
            viewProjectionMatrix.m33() + viewProjectionMatrix.m23()
        );

        frustumPlanes[5].set(
            viewProjectionMatrix.m30() - viewProjectionMatrix.m20(),
            viewProjectionMatrix.m31() - viewProjectionMatrix.m21(),
            viewProjectionMatrix.m32() - viewProjectionMatrix.m22(),
            viewProjectionMatrix.m33() - viewProjectionMatrix.m23()
        );

        for (Vector4f plane : frustumPlanes) {
            float length = (float) Math.sqrt(plane.x * plane.x + plane.y * plane.y + plane.z * plane.z);
            if (length > 0) {
                plane.div(length);
            }
        }

        frustumValid = true;
    }

    public boolean isAABBVisible(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        if (!frustumValid) {
            return true;
        }

        for (Vector4f plane : frustumPlanes) {
            float px = plane.x >= 0 ? maxX : minX;
            float py = plane.y >= 0 ? maxY : minY;
            float pz = plane.z >= 0 ? maxZ : minZ;

            if (plane.x * px + plane.y * py + plane.z * pz + plane.w < 0) {
                return false;
            }
        }

        return true;
    }

    public boolean isChunkVisible(int chunkX, int chunkZ, int minY, int maxY) {
        float minX = chunkX * 16.0f;
        float maxX = minX + 16.0f;
        float minZ = chunkZ * 16.0f;
        float maxZ = minZ + 16.0f;

        return isAABBVisible(minX, minY, minZ, maxX, maxY, maxZ);
    }

    public void updateFromCamera(Camera camera, Matrix4f projectionMatrix) {
        if (camera == null) return;

        Vec3d cameraPos = camera.getPos();

        Matrix4f viewMatrix = new Matrix4f();
        viewMatrix.identity();
        viewMatrix.rotateX((float) Math.toRadians(-camera.getPitch()));
        viewMatrix.rotateY((float) Math.toRadians(-camera.getYaw() + 180));
        viewMatrix.translate(-(float)cameraPos.x, -(float)cameraPos.y, -(float)cameraPos.z);

        Matrix4f viewProjection = new Matrix4f(projectionMatrix);
        viewProjection.mul(viewMatrix);

        updateFrustum(viewProjection);
    }

    public boolean isFrustumValid() {
        return frustumValid;
    }
}