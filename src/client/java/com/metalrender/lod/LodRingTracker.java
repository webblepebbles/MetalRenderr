package com.metalrender.lod;

import com.metalrender.config.MetalRenderConfig;
import com.metalrender.util.MetalLogger;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

public final class LodRingTracker {

    
    public static final class Ring {
        public final int index;
        public final int minChunkDist; 
        public final int maxChunkDist; 
        public final int lodLevel; 

        Ring(int index, int minChunkDist, int maxChunkDist, int lodLevel) {
            this.index = index;
            this.minChunkDist = minChunkDist;
            this.maxChunkDist = maxChunkDist;
            this.lodLevel = lodLevel;
        }

        
        public int minSectionDist() {
            return minChunkDist;
        }

        public int maxSectionDist() {
            return maxChunkDist;
        }
    }
    private static final Ring[] RINGS = {
            new Ring(0, 0, 20, -1), 
            new Ring(1, 20, 48, 2), 
            new Ring(2, 48, 96, 3), 
            new Ring(3, 96, 192, 5), 
            new Ring(4, 192, 256, 5), 
    };

    
    private static final int MANAGED_RING_START = 1;
    private int camSecX = Integer.MIN_VALUE;
    private int camSecY = Integer.MIN_VALUE;
    private int camSecZ = Integer.MIN_VALUE;
    private final LongOpenHashSet activeSections = new LongOpenHashSet();
    private final Long2IntOpenHashMap sectionLodLevel = new Long2IntOpenHashMap();
    private int maxRenderDistChunks = 256;
    private int minSectionY = -4;
    private int maxSectionY = 20; 

    
    public static final class UpdateDelta {
        
        public final long[] toLoad;
        
        public final int[] loadLodLevels;
        
        public final long[] toUnload;
        
        public final boolean cameraMoved;

        UpdateDelta(long[] toLoad, int[] loadLodLevels, long[] toUnload, boolean cameraMoved) {
            this.toLoad = toLoad;
            this.loadLodLevels = loadLodLevels;
            this.toUnload = toUnload;
            this.cameraMoved = cameraMoved;
        }

        public static final UpdateDelta EMPTY = new UpdateDelta(
                new long[0], new int[0], new long[0], false);
    }

    public LodRingTracker() {
        sectionLodLevel.defaultReturnValue(-1);
    }

    
    public UpdateDelta update(int blockX, int blockY, int blockZ) {
        int newSecX = blockX >> 4;
        int newSecY = blockY >> 4;
        int newSecZ = blockZ >> 4;
        if (newSecX == camSecX && newSecY == camSecY && newSecZ == camSecZ) {
            return UpdateDelta.EMPTY;
        }

        boolean firstUpdate = (camSecX == Integer.MIN_VALUE);
        camSecX = newSecX;
        camSecY = newSecY;
        camSecZ = newSecZ;
        maxRenderDistChunks = MetalRenderConfig.extremeRenderDistance();
        LongOpenHashSet newActive = new LongOpenHashSet();
        Long2IntOpenHashMap newLodMap = new Long2IntOpenHashMap();
        newLodMap.defaultReturnValue(-1);

        for (int ringIdx = MANAGED_RING_START; ringIdx < RINGS.length; ringIdx++) {
            Ring ring = RINGS[ringIdx];
            if (ring.minChunkDist >= maxRenderDistChunks)
                break; 

            int effectiveMax = Math.min(ring.maxChunkDist, maxRenderDistChunks);
            int minDist = ring.minSectionDist();
            int maxDist = Math.min(ring.maxSectionDist(), maxRenderDistChunks);

            for (int sx = camSecX - maxDist; sx <= camSecX + maxDist; sx++) {
                for (int sz = camSecZ - maxDist; sz <= camSecZ + maxDist; sz++) {
                    int dx = Math.abs(sx - camSecX);
                    int dz = Math.abs(sz - camSecZ);
                    int horizDist = Math.max(dx, dz);

                    if (horizDist < minDist || horizDist >= effectiveMax)
                        continue;
                    for (int sy = minSectionY; sy < maxSectionY; sy++) {
                        long key = LodSection.packKey(sx, sy, sz);
                        newActive.add(key);
                        newLodMap.put(key, ring.lodLevel);
                    }
                }
            }
        }
        LongArrayList toLoad = new LongArrayList();
        LongArrayList toLodLevels = new LongArrayList();
        LongArrayList toUnload = new LongArrayList();
        for (long key : newActive) {
            if (!activeSections.contains(key)) {
                toLoad.add(key);
                toLodLevels.add(newLodMap.get(key));
            }
        }
        for (long key : activeSections) {
            if (!newActive.contains(key)) {
                toUnload.add(key);
            }
        }
        activeSections.clear();
        activeSections.addAll(newActive);
        sectionLodLevel.clear();
        sectionLodLevel.putAll(newLodMap);

        if (!firstUpdate && (toLoad.size() > 0 || toUnload.size() > 0)) {
            MetalLogger.info("[LodRingTracker] Camera at section ({},{},{}): " +
                    "+{} load, -{} unload, {} active",
                    camSecX, camSecY, camSecZ,
                    toLoad.size(), toUnload.size(), activeSections.size());
        }

        int[] lodLevelsArr = new int[toLodLevels.size()];
        for (int i = 0; i < toLodLevels.size(); i++) {
            lodLevelsArr[i] = (int) toLodLevels.getLong(i);
        }

        return new UpdateDelta(
                toLoad.toLongArray(),
                lodLevelsArr,
                toUnload.toLongArray(),
                true);
    }

    
    public int getLodLevel(long sectionKey) {
        return sectionLodLevel.get(sectionKey);
    }

    
    public static Ring getRingForLod(int lodLevel) {
        for (Ring ring : RINGS) {
            if (ring.lodLevel == lodLevel)
                return ring;
        }
        return RINGS[RINGS.length - 1];
    }

    
    public static Ring[] getRings() {
        return RINGS;
    }

    
    public boolean isActive(long sectionKey) {
        return activeSections.contains(sectionKey);
    }

    
    public int getActiveSectionCount() {
        return activeSections.size();
    }

    
    public void clear() {
        activeSections.clear();
        sectionLodLevel.clear();
        camSecX = Integer.MIN_VALUE;
        camSecY = Integer.MIN_VALUE;
        camSecZ = Integer.MIN_VALUE;
    }

    
    public void setYBounds(int minSectionY, int maxSectionY) {
        this.minSectionY = minSectionY;
        this.maxSectionY = maxSectionY;
    }
}
