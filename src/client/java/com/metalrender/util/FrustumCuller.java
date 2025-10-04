package com.metalrender.util;

import net.minecraft.client.render.Camera;
import net.minecraft.util.math.Vec3d;

public final class FrustumCuller {
    private double[] frustumPlanes = new double[24];
    private boolean frustumValid = false;
    private static final int SECTOR_SIZE = 4;

    public void updateFrustum(Camera camera, float fovDegrees, float aspect, float nearPlane, float farPlane) {
        Vec3d pos = camera.getPos();
        Vec3d forward =
            new Vec3d(camera.getHorizontalPlane().x, camera.getHorizontalPlane().y, camera.getHorizontalPlane().z);
        Vec3d up = new Vec3d(camera.getVerticalPlane().x, camera.getVerticalPlane().y, camera.getVerticalPlane().z);
        Vec3d right = forward.crossProduct(up).normalize();

        double fovRad = Math.toRadians(fovDegrees);
        double halfHeight = Math.tan(fovRad * 0.5) * nearPlane;
        double halfWidth = halfHeight * aspect;

        Vec3d nearCenter = pos.add(forward.multiply(nearPlane));
        Vec3d farCenter = pos.add(forward.multiply(farPlane));

        Vec3d nearTopLeft = nearCenter.add(up.multiply(halfHeight)).subtract(right.multiply(halfWidth));
        Vec3d nearTopRight = nearCenter.add(up.multiply(halfHeight)).add(right.multiply(halfWidth));
        Vec3d nearBottomLeft = nearCenter.subtract(up.multiply(halfHeight)).subtract(right.multiply(halfWidth));
        Vec3d nearBottomRight = nearCenter.subtract(up.multiply(halfHeight)).add(right.multiply(halfWidth));

        Vec3d farTopLeft = farCenter.add(up.multiply(halfHeight * farPlane / nearPlane))
                               .subtract(right.multiply(halfWidth * farPlane / nearPlane));
        Vec3d farBottomRight = farCenter.subtract(up.multiply(halfHeight * farPlane / nearPlane))
                                   .add(right.multiply(halfWidth * farPlane / nearPlane));

        extractPlane(0, nearTopLeft, nearTopRight, farTopLeft);
        extractPlane(1, nearBottomRight, nearBottomLeft, farBottomRight);
        extractPlane(2, nearBottomLeft, nearTopLeft, farTopLeft);
        extractPlane(3, nearTopRight, nearBottomRight, farBottomRight);
        extractPlane(4, nearTopLeft, nearBottomLeft, nearBottomRight);
        extractPlane(5, farTopLeft, farBottomRight, farTopLeft);

        frustumValid = true;
    }

    private void extractPlane(int index, Vec3d p1, Vec3d p2, Vec3d p3) {
        Vec3d v1 = p2.subtract(p1);
        Vec3d v2 = p3.subtract(p1);
        Vec3d normal = v1.crossProduct(v2).normalize();
        double d = -normal.dotProduct(p1);
        int offset = index * 4;
        frustumPlanes[offset] = normal.x;
        frustumPlanes[offset + 1] = normal.y;
        frustumPlanes[offset + 2] = normal.z;
        frustumPlanes[offset + 3] = d;
    }

    public boolean isChunkVisible(int chunkX, int chunkZ, int minY, int maxY) {
        if (!frustumValid)
            return true;

        return isChunkVisibleDirect(chunkX, chunkZ, minY, maxY);
    }

    public boolean isSectorVisible(int sectorX, int sectorZ, int minY, int maxY) {
        if (!frustumValid)
            return true;

        int chunkMinX = sectorX * SECTOR_SIZE;
        int chunkMaxX = chunkMinX + SECTOR_SIZE - 1;
        int chunkMinZ = sectorZ * SECTOR_SIZE;
        int chunkMaxZ = chunkMinZ + SECTOR_SIZE - 1;

        double minX = chunkMinX * 16.0;
        double maxX = (chunkMaxX + 1) * 16.0;
        double minZ = chunkMinZ * 16.0;
        double maxZ = (chunkMaxZ + 1) * 16.0;

        for (int i = 0; i < 6; i++) {
            int offset = i * 4;
            double nx = frustumPlanes[offset];
            double ny = frustumPlanes[offset + 1];
            double nz = frustumPlanes[offset + 2];
            double d = frustumPlanes[offset + 3];

            double pX = nx > 0 ? maxX : minX;
            double pY = ny > 0 ? maxY : minY;
            double pZ = nz > 0 ? maxZ : minZ;

            if (nx * pX + ny * pY + nz * pZ + d < 0) {
                return false;
            }
        }
        return true;
    }

    private boolean isChunkVisibleDirect(int chunkX, int chunkZ, int minY, int maxY) {
        double minX = chunkX * 16.0;
        double maxX = minX + 16.0;
        double minZ = chunkZ * 16.0;
        double maxZ = minZ + 16.0;

        for (int i = 0; i < 6; i++) {
            int offset = i * 4;
            double nx = frustumPlanes[offset];
            double ny = frustumPlanes[offset + 1];
            double nz = frustumPlanes[offset + 2];
            double d = frustumPlanes[offset + 3];

            double pX = nx > 0 ? maxX : minX;
            double pY = ny > 0 ? maxY : minY;
            double pZ = nz > 0 ? maxZ : minZ;

            if (nx * pX + ny * pY + nz * pZ + d < 0) {
                return false;
            }
        }
        return true;
    }

    public boolean isPointVisible(double x, double y, double z) {
        if (!frustumValid)
            return true;

        for (int i = 0; i < 6; i++) {
            int offset = i * 4;
            double distance = frustumPlanes[offset] * x + frustumPlanes[offset + 1] * y + frustumPlanes[offset + 2] * z
                + frustumPlanes[offset + 3];
            if (distance < 0)
                return false;
        }
        return true;
    }
}