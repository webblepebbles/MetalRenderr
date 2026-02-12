package com.metalrender.lod;

import com.metalrender.config.MetalRenderConfig;
import com.metalrender.nativebridge.NativeBridge;
import com.metalrender.render.GpuLodOrchestrator;
import com.metalrender.util.MetalLogger;
import com.metalrender.util.PersistentBufferArena;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.joml.Matrix4f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class ExtendedLodRenderer {

    private static final int MAX_GPU_SECTIONS = 32768;
    private static final int MAX_UPLOADS_PER_FRAME = 64;
    private static final int MAX_EVICTS_PER_FRAME = 128;
    private static final int FIELDS_PER_DRAW = 8;
    private static final int BYTES_PER_DRAW = FIELDS_PER_DRAW * 4;
    private static final int INITIAL_BATCH_CAPACITY = 4096;

    private static final class GpuSection {
        final long sectionKey;
        final int arenaOffset;
        final int vertexCount;
        final int vertexStride;
        final int dataSize;
        final int lodLevel;
        final float originX, originY, originZ;
        final float boundsRadius;
        long lastUsedFrame;

        GpuSection(long key, int offset, int vtxCount, int stride, int dataSize,
                int lod, float ox, float oy, float oz, float radius) {
            this.sectionKey = key;
            this.arenaOffset = offset;
            this.vertexCount = vtxCount;
            this.vertexStride = stride;
            this.dataSize = dataSize;
            this.lodLevel = lod;
            this.originX = ox;
            this.originY = oy;
            this.originZ = oz;
            this.boundsRadius = radius;
            this.lastUsedFrame = 0;
        }
    }

    private final Long2ObjectOpenHashMap<GpuSection> gpuSections = new Long2ObjectOpenHashMap<>();
    private final LongOpenHashSet pendingUpload = new LongOpenHashSet();
    private final LongOpenHashSet pendingEvict = new LongOpenHashSet();
    private final ExtendedLodCuller culler = new ExtendedLodCuller();

    private ByteBuffer batchBuffer;
    private PersistentBufferArena arena;
    private long nativeHandle;

    private GpuLodOrchestrator icbOrchestrator;
    private boolean useICB = false;

    private float[] icbCameraPos = new float[3];
    private float[] icbFrustumPlanes = new float[24];

    private long currentFrame = 0;
    private int uploadsThisFrame = 0;
    private int drawsThisFrame = 0;
    private int totalGpuBytes = 0;
    private boolean initialized = false;

    public ExtendedLodRenderer() {
    }

    public boolean initialize(long nativeHandle, PersistentBufferArena arena) {
        if (nativeHandle == 0L || arena == null || arena.buffer() == null) {
            MetalLogger.warn("[ExtLodRenderer] Cannot init: invalid handle or arena");
            return false;
        }
        this.nativeHandle = nativeHandle;
        this.arena = arena;
        this.batchBuffer = ByteBuffer.allocateDirect(INITIAL_BATCH_CAPACITY * BYTES_PER_DRAW)
                .order(ByteOrder.nativeOrder());

        try {
            this.icbOrchestrator = new GpuLodOrchestrator(nativeHandle);
            if (this.icbOrchestrator.initialize()) {
                this.useICB = true;
                MetalLogger.info("[ExtLodRenderer] ICB rendering ACTIVE via GpuLodOrchestrator");
            } else {
                this.useICB = false;
                MetalLogger.info("[ExtLodRenderer] ICB unavailable, using batch draw fallback");
            }
        } catch (Throwable e) {
            this.useICB = false;
            MetalLogger.warn("[ExtLodRenderer] ICB init failed: {}, using batch fallback", e.getMessage());
        }

        this.initialized = true;
        MetalLogger.info("[ExtLodRenderer] Initialized (arena capacity=%d MB, ICB=%s)",
                arena.capacity() / (1024 * 1024), useICB ? "YES" : "NO");
        return true;
    }

    public void processRingDelta(LodRingTracker.UpdateDelta delta,
            LodRingTracker tracker,
            LodChunkStorage storage) {
        if (!initialized || delta == null)
            return;

        for (int i = 0; i < delta.toLoad.length; i++) {
            long key = delta.toLoad[i];
            if (!gpuSections.containsKey(key)) {
                pendingUpload.add(key);
            }
        }

        for (long key : delta.toUnload) {
            pendingEvict.add(key);
            pendingUpload.remove(key);
        }

        int evicted = 0;
        LongArrayList evictedKeys = new LongArrayList();
        for (long key : pendingEvict) {
            GpuSection gpu = gpuSections.remove(key);
            if (gpu != null) {
                arena.free(gpu.arenaOffset, gpu.dataSize);
                totalGpuBytes -= gpu.dataSize;
            }
            evictedKeys.add(key);
            if (++evicted >= MAX_EVICTS_PER_FRAME)
                break;
        }
        for (int i = 0; i < evictedKeys.size(); i++) {
            pendingEvict.remove(evictedKeys.getLong(i));
        }

        int uploaded = 0;
        LongArrayList uploadedKeys = new LongArrayList();
        for (long key : pendingUpload) {
            if (uploaded >= MAX_UPLOADS_PER_FRAME)
                break;
            if (gpuSections.size() >= MAX_GPU_SECTIONS)
                break;

            LodSection section = storage.getSection(key);
            if (section == null || section.isEmpty()) {
                uploadedKeys.add(key);
                continue;
            }
            int lodLevel = tracker.getLodLevel(key);
            if (lodLevel < 0)
                lodLevel = 3;

            GpuSection gpuSec = uploadSection(section, lodLevel);
            if (gpuSec != null) {
                gpuSections.put(key, gpuSec);
                uploaded++;
            }
            uploadedKeys.add(key);
        }
        for (int i = 0; i < uploadedKeys.size(); i++) {
            pendingUpload.remove(uploadedKeys.getLong(i));
        }
        uploadsThisFrame = uploaded;
    }

    private GpuSection uploadSection(LodSection section, int lodLevel) {
        if (section == null || section.isEmpty())
            return null;

        ByteBuffer vertexData;
        int vertexCount;
        int vertexStride;

        if (lodLevel <= 2) {
            vertexData = section.getTexturedVertices();
            vertexCount = section.getTexturedVertexCount();
            vertexStride = LodVertexFormat.STRIDE_LOD_TEXTURED;
        } else {
            vertexData = section.getCompactVertices();
            vertexCount = section.getCompactVertexCount();
            vertexStride = LodVertexFormat.STRIDE_LOD_COMPACT;
        }

        if (vertexData == null || vertexCount == 0)
            return null;
        int dataSize = vertexCount * vertexStride;

        int offset = arena.allocate(dataSize);
        if (offset < 0) {
            evictOldest(dataSize * 2);
            offset = arena.allocate(dataSize);
            if (offset < 0)
                return null;
        }

        ByteBuffer mapped = arena.buffer();
        if (mapped == null)
            return null;

        ByteBuffer src = vertexData.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        src.position(0).limit(dataSize);
        ByteBuffer dst = mapped.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        dst.position(offset).limit(offset + dataSize);
        dst.put(src);

        totalGpuBytes += dataSize;
        return new GpuSection(
                section.key(), offset, vertexCount, vertexStride, dataSize, lodLevel,
                section.worldOriginX(), section.worldOriginY(), section.worldOriginZ(),
                section.getBoundsRadius());
    }

    private void evictOldest(int bytesNeeded) {
        if (gpuSections.isEmpty())
            return;
        LongArrayList toEvict = new LongArrayList();
        long threshold = currentFrame - 60;
        int freed = 0;

        for (var entry : gpuSections.long2ObjectEntrySet()) {
            if (entry.getValue().lastUsedFrame < threshold) {
                toEvict.add(entry.getLongKey());
                freed += entry.getValue().dataSize;
                if (freed >= bytesNeeded)
                    break;
            }
        }
        for (int i = 0; i < toEvict.size(); i++) {
            long key = toEvict.getLong(i);
            GpuSection gpu = gpuSections.remove(key);
            if (gpu != null) {
                arena.free(gpu.arenaOffset, gpu.dataSize);
                totalGpuBytes -= gpu.dataSize;
            }
        }
    }

    public int renderFrame(Matrix4f viewProj, float camX, float camY, float camZ,
            float cameraPitch, int altitudeAboveGround, long frameTimeNs) {
        if (!initialized || nativeHandle == 0L)
            return 0;

        currentFrame++;
        culler.recordFrameTime(frameTimeNs);
        culler.beginFrame(viewProj, camX, camY, camZ, cameraPitch, altitudeAboveGround);

        if (useICB && icbOrchestrator != null && icbOrchestrator.isInitialized()) {
            return renderFrameICB(camX, camY, camZ);
        }

        return renderFrameBatch(camX, camY, camZ);
    }

    private int renderFrameICB(float camX, float camY, float camZ) {
        icbCameraPos[0] = camX;
        icbCameraPos[1] = camY;
        icbCameraPos[2] = camZ;

        culler.extractFrustumPlanes(icbFrustumPlanes);

        int totalChunks = gpuSections.size();
        if (totalChunks == 0) {
            drawsThisFrame = 0;
            return 0;
        }

        for (var entry : gpuSections.long2ObjectEntrySet()) {
            entry.getValue().lastUsedFrame = currentFrame;
        }

        int commandCount = icbOrchestrator.renderFrame(
                icbCameraPos, icbFrustumPlanes, totalChunks);

        drawsThisFrame = commandCount;

        if (currentFrame <= 5 || currentFrame % 300 == 0) {
            int[] lodCounts = icbOrchestrator.getAllLodDrawCounts();
            MetalLogger.info("[ExtLodRenderer-ICB] Frame %d: %d draws (of %d gpu) | "
                    + "LOD [%d,%d,%d,%d,%d,%d] | gpu=%d MB, pending=%d",
                    currentFrame, commandCount, gpuSections.size(),
                    lodCounts[0], lodCounts[1], lodCounts[2],
                    lodCounts[3], lodCounts[4], lodCounts[5],
                    totalGpuBytes / (1024 * 1024), pendingUpload.size());
        }

        return commandCount;
    }

    private int renderFrameBatch(float camX, float camY, float camZ) {

        int maxDraws = Math.max(1, gpuSections.size());
        ensureBatchCapacity(maxDraws);
        batchBuffer.clear();

        int commandCount = 0;
        for (var entry : gpuSections.long2ObjectEntrySet()) {
            GpuSection gpu = entry.getValue();

            int sectionX = LodSection.unpackX(gpu.sectionKey);
            int sectionY = LodSection.unpackY(gpu.sectionKey);
            int sectionZ = LodSection.unpackZ(gpu.sectionKey);

            if (!culler.shouldRender(sectionX, sectionY, sectionZ, gpu.boundsRadius)) {
                continue;
            }

            gpu.lastUsedFrame = currentFrame;

            float dx = gpu.originX + 8.0f - camX;
            float dy = gpu.originY + 8.0f - camY;
            float dz = gpu.originZ + 8.0f - camZ;
            float distSq = dx * dx + dy * dy + dz * dz;

            int extLodLevel = gpu.lodLevel + 10;

            batchBuffer.putInt(gpu.arenaOffset / 4);
            batchBuffer.putInt(gpu.vertexCount);
            batchBuffer.putFloat(gpu.originX);
            batchBuffer.putFloat(gpu.originY);
            batchBuffer.putFloat(gpu.originZ);
            batchBuffer.putInt(0);
            batchBuffer.putFloat(distSq);
            batchBuffer.putInt(extLodLevel);
            commandCount++;
        }

        if (commandCount == 0) {
            drawsThisFrame = 0;
            return 0;
        }

        batchBuffer.flip();
        NativeBridge.nBatchDrawCommands(nativeHandle, batchBuffer, commandCount);
        drawsThisFrame = commandCount;

        if (currentFrame <= 5 || currentFrame % 300 == 0) {
            MetalLogger.info("[ExtLodRenderer] Frame %d: %d draws (of %d gpu), "
                    + "culled: frust=%d horiz=%d budget=%d y=%d | "
                    + "gpu=%d MB, pending=%d, budget=%d, %.1fms",
                    currentFrame, commandCount, gpuSections.size(),
                    culler.getFrustumCulled(), culler.getHorizonCulled(),
                    culler.getBudgetCulled(), culler.getYCulled(),
                    totalGpuBytes / (1024 * 1024), pendingUpload.size(),
                    culler.getCurrentBudget(), culler.getSmoothedFrameMs());
        }
        return commandCount;
    }

    private void ensureBatchCapacity(int maxCommands) {
        int needed = Math.max(1, maxCommands) * BYTES_PER_DRAW;
        if (batchBuffer == null || batchBuffer.capacity() < needed) {
            batchBuffer = ByteBuffer.allocateDirect(needed).order(ByteOrder.nativeOrder());
        }
    }

    public void destroy() {
        if (icbOrchestrator != null) {
            icbOrchestrator.destroy();
            icbOrchestrator = null;
            useICB = false;
        }

        if (arena != null) {
            for (var entry : gpuSections.long2ObjectEntrySet()) {
                GpuSection gpu = entry.getValue();
                arena.free(gpu.arenaOffset, gpu.dataSize);
            }
        }
        gpuSections.clear();
        pendingUpload.clear();
        pendingEvict.clear();
        totalGpuBytes = 0;
        initialized = false;
        MetalLogger.info("[ExtLodRenderer] Destroyed (%d frames rendered)", currentFrame);
    }

    public boolean isInitialized() {
        return initialized;
    }

    public int getGpuSectionCount() {
        return gpuSections.size();
    }

    public int getPendingUploadCount() {
        return pendingUpload.size();
    }

    public int getTotalGpuBytes() {
        return totalGpuBytes;
    }

    public int getDrawsLastFrame() {
        return drawsThisFrame;
    }

    public int getUploadsLastFrame() {
        return uploadsThisFrame;
    }

    public ExtendedLodCuller getCuller() {
        return culler;
    }

    public String getDebugInfo() {
        return String.format("ExtLOD: %d gpu, %d draws, %d MB, budget=%d, %.1fms | "
                + "cull: frust=%d horiz=%d y=%d budg=%d",
                gpuSections.size(), drawsThisFrame, totalGpuBytes / (1024 * 1024),
                culler.getCurrentBudget(), culler.getSmoothedFrameMs(),
                culler.getFrustumCulled(), culler.getHorizonCulled(),
                culler.getYCulled(), culler.getBudgetCulled());
    }
}
