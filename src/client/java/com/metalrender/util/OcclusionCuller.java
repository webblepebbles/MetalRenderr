package com.metalrender.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.block.BlockState;
import java.util.Map;
import java.util.HashMap;

public class OcclusionCuller {
    private final Map<BlockPos, Boolean> occlusionCache = new HashMap<>();
    private Vec3d lastCameraPos = Vec3d.ZERO;
    private long lastUpdateTime = 0;
    private static final long CACHE_DURATION_MS = 100;
    private static final int MAX_RAY_DISTANCE = 64;
    private static final int RAY_STEP_SIZE = 2;

    public boolean isChunkOccluded(BlockPos chunkPos, Camera camera) {
        if (camera == null) return false;

        Vec3d cameraPos = camera.getPos();
        long currentTime = System.currentTimeMillis();

        if (lastCameraPos.distanceTo(cameraPos) > 5.0 || currentTime - lastUpdateTime > CACHE_DURATION_MS) {
            occlusionCache.clear();
            lastCameraPos = cameraPos;
            lastUpdateTime = currentTime;
        }

        Boolean cached = occlusionCache.get(chunkPos);
        if (cached != null) {
            return cached;
        }

        boolean occluded = performOcclusionTest(chunkPos, cameraPos);
        occlusionCache.put(chunkPos, occluded);

        return occluded;
    }

    private boolean performOcclusionTest(BlockPos chunkPos, Vec3d cameraPos) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.world == null) return false;

        ClientWorld world = mc.world;

        Vec3d chunkCenter = new Vec3d(
            (chunkPos.getX() << 4) + 8,
            chunkPos.getY() + 64,
            (chunkPos.getZ() << 4) + 8
        );

        Vec3d direction = chunkCenter.subtract(cameraPos).normalize();
        double distance = cameraPos.distanceTo(chunkCenter);

        if (distance < 32.0) {
            return false;
        }

        distance = Math.min(distance, MAX_RAY_DISTANCE);

        int solidBlocksHit = 0;
        int totalSteps = (int) (distance / RAY_STEP_SIZE);

        for (int step = 1; step < totalSteps; step++) {
            double t = (step * RAY_STEP_SIZE) / distance;
            Vec3d testPos = cameraPos.add(direction.multiply(distance * t));

            BlockPos blockPos = BlockPos.ofFloored(testPos);
            BlockState state = world.getBlockState(blockPos);

            if (!state.isAir() && state.isOpaque()) {
                solidBlocksHit++;
                if (solidBlocksHit >= 3) {
                    return true;
                }
            }
        }

        return false;
    }

    public boolean isChunkOccludedAdvanced(BlockPos chunkPos, Camera camera) {
        if (camera == null) return false;

        Vec3d cameraPos = camera.getPos();

        Vec3d[] testPoints = {
            new Vec3d((chunkPos.getX() << 4) + 2, chunkPos.getY() + 32, (chunkPos.getZ() << 4) + 2),
            new Vec3d((chunkPos.getX() << 4) + 14, chunkPos.getY() + 32, (chunkPos.getZ() << 4) + 2),
            new Vec3d((chunkPos.getX() << 4) + 2, chunkPos.getY() + 32, (chunkPos.getZ() << 4) + 14),
            new Vec3d((chunkPos.getX() << 4) + 14, chunkPos.getY() + 32, (chunkPos.getZ() << 4) + 14),
            new Vec3d((chunkPos.getX() << 4) + 8, chunkPos.getY() + 64, (chunkPos.getZ() << 4) + 8),
            new Vec3d((chunkPos.getX() << 4) + 8, chunkPos.getY() + 16, (chunkPos.getZ() << 4) + 8)
        };

        int visiblePoints = 0;

        for (Vec3d testPoint : testPoints) {
            if (!isPointOccluded(testPoint, cameraPos)) {
                visiblePoints++;
                if (visiblePoints >= 1) {
                    return false;
                }
            }
        }

        return true;
    }

    private boolean isPointOccluded(Vec3d point, Vec3d cameraPos) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.world == null) return false;

        ClientWorld world = mc.world;
        Vec3d direction = point.subtract(cameraPos).normalize();
        double distance = cameraPos.distanceTo(point);

        int steps = (int) (distance / RAY_STEP_SIZE);

        for (int step = 1; step < steps; step++) {
            double t = (step * RAY_STEP_SIZE) / distance;
            Vec3d testPos = cameraPos.add(direction.multiply(distance * t));

            BlockPos blockPos = BlockPos.ofFloored(testPos);
            BlockState state = world.getBlockState(blockPos);

            if (!state.isAir() && state.isOpaque()) {
                return true;
            }
        }

        return false;
    }

    public void clearCache() {
        occlusionCache.clear();
    }

    public int getCacheSize() {
        return occlusionCache.size();
    }
}