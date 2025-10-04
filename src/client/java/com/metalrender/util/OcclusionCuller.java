package com.metalrender.util;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

public final class OcclusionCuller {
    private final int MAX_RAY_DISTANCE = 64;
    private final double STEP_SIZE = 0.5;
    private final Map<BlockPos, Long> occlusionCache = new ConcurrentHashMap<>();
    private final Map<BlockPos, Integer> chunkFrameCounter = new ConcurrentHashMap<>();
    private long currentFrame = 0;
    private final SpatialCullingCache spatialCache = new SpatialCullingCache();

    public boolean isChunkOccluded(BlockPos chunkPos, Camera camera) {
        currentFrame++;
        spatialCache.advanceFrame();

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null)
            return false;

        Vec3d cameraPos = camera.getPos();
        Vec3d chunkCenter = new Vec3d(chunkPos.getX() + 8, chunkPos.getY() + 8, chunkPos.getZ() + 8);

        double distance = cameraPos.distanceTo(chunkCenter);
        if (distance > MAX_RAY_DISTANCE || distance < 16)
            return false;
        if (!spatialCache.shouldTestChunk(chunkPos, cameraPos)) {
            SpatialCullingCache.ChunkCacheEntry cached = spatialCache.getCachedResult(chunkPos);
            if (cached != null) {
                return cached.occluded;
            }
        }

        Integer lastFrame = chunkFrameCounter.get(chunkPos);
        int framesSinceTest = lastFrame != null ? (int) (currentFrame - lastFrame) : 999;

        int testFrequency = distance < 32 ? 3 : distance < 64 ? 5 : 8;

        if (framesSinceTest < testFrequency) {
            Long cachedResult = occlusionCache.get(chunkPos);
            if (cachedResult != null) {
                return cachedResult == 1L;
            }
        }

        boolean occluded = isPathBlocked(client.world, cameraPos, chunkCenter);

        occlusionCache.put(chunkPos, occluded ? 1L : 0L);
        chunkFrameCounter.put(chunkPos, (int) currentFrame);
        spatialCache.cacheVisibilityResult(chunkPos, !occluded, occluded, cameraPos);

        if (occlusionCache.size() > 1000) {
            occlusionCache.clear();
            chunkFrameCounter.clear();
        }

        return occluded;
    }

    private boolean isPathBlocked(World world, Vec3d start, Vec3d end) {
        Vec3d direction = end.subtract(start).normalize();
        double totalDistance = start.distanceTo(end);
        int steps = (int) (totalDistance / STEP_SIZE);

        int consecutiveBlocks = 0;
        for (int i = 1; i < steps; i++) {
            Vec3d currentPos = start.add(direction.multiply(i * STEP_SIZE));
            BlockPos blockPos = new BlockPos((int) currentPos.x, (int) currentPos.y, (int) currentPos.z);

            if (blockPos.getY() < world.getBottomY() || blockPos.getY() >= world.getHeight())
                continue;

            BlockState state = world.getBlockState(blockPos);
            if (!state.isAir() && state.isOpaque()) {
                consecutiveBlocks++;
                if (consecutiveBlocks >= 3)
                    return true;
            } else {
                consecutiveBlocks = 0;
            }
        }
        return false;
    }

    public boolean isBlockVisible(BlockPos pos, Camera camera) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null)
            return true;

        Vec3d cameraPos = camera.getPos();
        Vec3d blockCenter = new Vec3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);

        double distance = cameraPos.distanceTo(blockCenter);
        if (distance > MAX_RAY_DISTANCE || distance < 2)
            return true;

        return !isPathBlocked(client.world, cameraPos, blockCenter);
    }
}