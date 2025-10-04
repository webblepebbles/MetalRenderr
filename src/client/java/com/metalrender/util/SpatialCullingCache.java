package com.metalrender.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

public final class SpatialCullingCache {
    private static final int SECTOR_SIZE = 8;
    private static final int MAX_CACHE_AGE = 30;

    private final Map<Long, SectorCache> sectorCache = new ConcurrentHashMap<>();
    private final Map<BlockPos, ChunkCacheEntry> chunkCache = new ConcurrentHashMap<>();
    private long currentFrame = 0;

    private static class SectorCache {
        final List<BlockPos> chunks = new ArrayList<>();
        long lastUsed = 0;
        boolean visible = true;

        SectorCache(long frame) {
            lastUsed = frame;
        }
    }

    public static class ChunkCacheEntry {
        boolean visible = true;
        boolean occluded = false;
        long lastTested = 0;
        int stabilityCount = 0;
        Vec3d lastCameraPos = Vec3d.ZERO;

        ChunkCacheEntry(boolean vis, boolean occ, long frame, Vec3d cameraPos) {
            visible = vis;
            occluded = occ;
            lastTested = frame;
            lastCameraPos = cameraPos;
        }
    }

    public void advanceFrame() {
        currentFrame++;

        if (currentFrame % 60 == 0) {
            cleanupCache();
        }
    }

    private void cleanupCache() {
        chunkCache.entrySet().removeIf(entry -> currentFrame - entry.getValue().lastTested > MAX_CACHE_AGE);

        sectorCache.entrySet().removeIf(entry -> currentFrame - entry.getValue().lastUsed > MAX_CACHE_AGE);
    }

    public boolean shouldTestChunk(BlockPos chunkPos, Vec3d cameraPos) {
        ChunkCacheEntry entry = chunkCache.get(chunkPos);
        if (entry == null)
            return true;

        // camera movement
        double cameraDist = entry.lastCameraPos.distanceTo(cameraPos);
        if (cameraDist > 32.0)
            return true;

        int testFrequency = Math.min(8, 2 + entry.stabilityCount);
        return currentFrame - entry.lastTested >= testFrequency;
    }

    public void cacheVisibilityResult(BlockPos chunkPos, boolean visible, boolean occluded, Vec3d cameraPos) {
        ChunkCacheEntry existing = chunkCache.get(chunkPos);

        if (existing != null && existing.visible == visible && existing.occluded == occluded) {
            existing.stabilityCount = Math.min(10, existing.stabilityCount + 1);
        } else {
            ChunkCacheEntry entry = new ChunkCacheEntry(visible, occluded, currentFrame, cameraPos);
            chunkCache.put(chunkPos, entry);
        }

        if (existing != null) {
            existing.lastTested = currentFrame;
            existing.lastCameraPos = cameraPos;
        }
    }

    public ChunkCacheEntry getCachedResult(BlockPos chunkPos) {
        return chunkCache.get(chunkPos);
    }

    public List<BlockPos> getNeighborChunks(BlockPos centerChunk) {
        List<BlockPos> neighbors = new ArrayList<>();
        int centerX = centerChunk.getX() >> 4;
        int centerZ = centerChunk.getZ() >> 4;

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0)
                    continue;
                BlockPos neighbor = new BlockPos((centerX + dx) << 4, centerChunk.getY(), (centerZ + dz) << 4);
                neighbors.add(neighbor);
            }
        }
        return neighbors;
    }

    public void invalidateRegion(BlockPos center, int radius) {
        int centerX = center.getX() >> 4;
        int centerZ = center.getZ() >> 4;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                BlockPos chunkPos = new BlockPos((centerX + dx) << 4, center.getY(), (centerZ + dz) << 4);
                chunkCache.remove(chunkPos);
            }
        }
    }

    public int getCacheSize() {
        return chunkCache.size();
    }

    public long getCurrentFrame() {
        return currentFrame;
    }
}