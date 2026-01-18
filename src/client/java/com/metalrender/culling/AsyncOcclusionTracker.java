package com.metalrender.culling;

import com.metalrender.util.MetalLogger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.math.Vec3d;


public final class AsyncOcclusionTracker {
    private static final int MAX_TRAVERSAL_DEPTH = 32; 
    private static final int SECTIONS_PER_TICK = 256; 
    private static final int NEIGHBOR_DIRECTIONS = 6; 

    
    private static final int[][] DIRECTIONS = {
            { 1, 0, 0 }, { -1, 0, 0 },
            { 0, 1, 0 }, { 0, -1, 0 },
            { 0, 0, 1 }, { 0, 0, -1 }
    };

    private final ExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger frameCounter = new AtomicInteger(0);

    
    private final Map<Long, SectionState> sectionStates = new ConcurrentHashMap<>();
    private final BitSet visitedThisFrame = new BitSet();

    
    private volatile Vec3d lastCameraPos = Vec3d.ZERO;
    private volatile ChunkSectionPos lastCameraSection = ChunkSectionPos.from(0, 0, 0);
    private volatile Future<?> currentTraversal = null;

    
    private final PriorityQueue<UploadCandidate> uploadQueue;
    private final Object queueLock = new Object();

    public AsyncOcclusionTracker() {
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "MetalRender-OcclusionTracker");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });

        this.uploadQueue = new PriorityQueue<>(
                Comparator.comparingDouble(UploadCandidate::priority).reversed());

        MetalLogger.info("[AsyncOcclusionTracker] Initialized");
    }

    
    public void updateCamera(Vec3d cameraPos) {
        if (cameraPos == null) {
            return;
        }

        ChunkSectionPos newSection = ChunkSectionPos.from(
                (int) Math.floor(cameraPos.x) >> 4,
                (int) Math.floor(cameraPos.y) >> 4,
                (int) Math.floor(cameraPos.z) >> 4);

        boolean sectionChanged = !newSection.equals(lastCameraSection);
        lastCameraPos = cameraPos;
        lastCameraSection = newSection;

        
        if (sectionChanged || frameCounter.incrementAndGet() % 30 == 0) {
            triggerTraversal();
        }
    }

    
    public void registerSection(BlockPos sectionOrigin) {
        long key = sectionOrigin.asLong();
        sectionStates.computeIfAbsent(key, k -> new SectionState(sectionOrigin));
    }

    
    public void unregisterSection(BlockPos sectionOrigin) {
        sectionStates.remove(sectionOrigin.asLong());
    }

    
    public void markVisible(BlockPos sectionOrigin, boolean visible) {
        SectionState state = sectionStates.get(sectionOrigin.asLong());
        if (state != null) {
            state.gpuVisible = visible;
            state.lastGpuCheckFrame = frameCounter.get();
        }
    }

    
    public boolean isLikelyVisible(BlockPos sectionOrigin) {
        SectionState state = sectionStates.get(sectionOrigin.asLong());
        if (state == null) {
            return true; 
        }
        return state.bfsVisible || state.gpuVisible;
    }

    
    public double getUploadPriority(BlockPos sectionOrigin) {
        SectionState state = sectionStates.get(sectionOrigin.asLong());
        if (state == null) {
            return 0.0;
        }

        
        
        
        

        double distanceSq = sectionOrigin.getSquaredDistance(
                lastCameraPos.x, lastCameraPos.y, lastCameraPos.z);
        double distancePriority = 1.0 / (1.0 + distanceSq / (256.0 * 256.0));

        double bfsPriority = state.bfsOrder > 0
                ? 1.0 / (1.0 + state.bfsOrder / 100.0)
                : 0.5;

        double gpuBoost = state.gpuVisible ? 1.5 : 1.0;

        return (distancePriority * 0.5 + bfsPriority * 0.5) * gpuBoost;
    }

    
    public List<BlockPos> getUploadPriorities(int maxCount) {
        List<BlockPos> result = new ArrayList<>(maxCount);

        synchronized (queueLock) {
            while (!uploadQueue.isEmpty() && result.size() < maxCount) {
                UploadCandidate candidate = uploadQueue.poll();
                if (candidate != null && sectionStates.containsKey(candidate.key)) {
                    result.add(candidate.origin);
                }
            }
        }

        return result;
    }

    
    private void triggerTraversal() {
        if (!running.compareAndSet(false, true)) {
            return; 
        }

        
        if (currentTraversal != null && !currentTraversal.isDone()) {
            currentTraversal.cancel(true);
        }

        final ChunkSectionPos startSection = lastCameraSection;
        final Vec3d cameraPos = lastCameraPos;

        currentTraversal = executor.submit(() -> {
            try {
                performBFS(startSection, cameraPos);
            } catch (Exception e) {
                MetalLogger.warn("[AsyncOcclusionTracker] BFS traversal failed", e);
            } finally {
                running.set(false);
            }
        });
    }

    
    private void performBFS(ChunkSectionPos start, Vec3d cameraPos) {
        visitedThisFrame.clear();

        Deque<TraversalNode> queue = new ArrayDeque<>();
        List<UploadCandidate> newCandidates = new ArrayList<>();

        
        long startKey = ChunkSectionPos.asLong(start.getX(), start.getY(), start.getZ());
        queue.add(new TraversalNode(start.getX(), start.getY(), start.getZ(), 0));
        visitedThisFrame.set((int) (startKey & 0x7FFFFFFF));

        int bfsOrder = 0;
        int processed = 0;

        while (!queue.isEmpty() && processed < SECTIONS_PER_TICK * MAX_TRAVERSAL_DEPTH) {
            TraversalNode node = queue.poll();
            processed++;

            if (node.depth > MAX_TRAVERSAL_DEPTH) {
                continue;
            }

            long nodeKey = ChunkSectionPos.asLong(node.x, node.y, node.z);
            SectionState state = sectionStates.get(nodeKey);

            if (state != null) {
                state.bfsVisible = true;
                state.bfsOrder = bfsOrder++;
                state.lastBfsFrame = frameCounter.get();

                
                double priority = getUploadPriority(state.origin);
                newCandidates.add(new UploadCandidate(nodeKey, state.origin, priority));
            }

            
            for (int[] dir : DIRECTIONS) {
                int nx = node.x + dir[0];
                int ny = node.y + dir[1];
                int nz = node.z + dir[2];

                
                if (ny < -4 || ny > 20) {
                    continue;
                }

                long neighborKey = ChunkSectionPos.asLong(nx, ny, nz);
                int hashIndex = (int) (neighborKey & 0x7FFFFFFF);

                if (!visitedThisFrame.get(hashIndex)) {
                    visitedThisFrame.set(hashIndex);
                    queue.add(new TraversalNode(nx, ny, nz, node.depth + 1));
                }
            }
        }

        
        synchronized (queueLock) {
            uploadQueue.clear();
            uploadQueue.addAll(newCandidates);
        }

        
        for (SectionState state : sectionStates.values()) {
            if (state.lastBfsFrame != frameCounter.get()) {
                state.bfsVisible = false;
            }
        }
    }

    
    public String getStats() {
        int total = sectionStates.size();
        int bfsVisible = 0;
        int gpuVisible = 0;

        for (SectionState state : sectionStates.values()) {
            if (state.bfsVisible)
                bfsVisible++;
            if (state.gpuVisible)
                gpuVisible++;
        }

        synchronized (queueLock) {
            return String.format("Sections: %d, BFS visible: %d, GPU visible: %d, Queue: %d",
                    total, bfsVisible, gpuVisible, uploadQueue.size());
        }
    }

    
    public void shutdown() {
        executor.shutdownNow();
        try {
            executor.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        sectionStates.clear();
        uploadQueue.clear();
        MetalLogger.info("[AsyncOcclusionTracker] Shutdown");
    }

    

    private static final class SectionState {
        final BlockPos origin;
        volatile boolean bfsVisible = false;
        volatile boolean gpuVisible = true;
        volatile int bfsOrder = Integer.MAX_VALUE;
        volatile int lastBfsFrame = 0;
        volatile int lastGpuCheckFrame = 0;

        SectionState(BlockPos origin) {
            this.origin = origin;
        }
    }

    private static final class TraversalNode {
        final int x, y, z;
        final int depth;

        TraversalNode(int x, int y, int z, int depth) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.depth = depth;
        }
    }

    private static final class UploadCandidate {
        final long key;
        final BlockPos origin;
        final double priority;

        UploadCandidate(long key, BlockPos origin, double priority) {
            this.key = key;
            this.origin = origin;
            this.priority = priority;
        }

        double priority() {
            return priority;
        }
    }
}
