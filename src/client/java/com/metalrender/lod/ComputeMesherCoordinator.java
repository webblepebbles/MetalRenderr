package com.metalrender.lod;

import com.metalrender.nativebridge.NativeBridge;
import com.metalrender.util.MetalLogger;
import com.metalrender.util.PersistentBufferArena;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class ComputeMesherCoordinator {
    private boolean available;
    private long deviceHandle;
    private PersistentBufferArena arena;
    private static final int STAGING_BUFFER_SIZE = 4096 * 4 + 4096;
    private static final int MAX_VERTS_PER_SECTION = 73728;
    private static final int COMPACT_VERTEX_STRIDE = LodVertexFormat.STRIDE_LOD_COMPACT;
    private static final ThreadLocal<ByteBuffer> STAGING_CACHE = ThreadLocal
            .withInitial(() -> ByteBuffer.allocateDirect(STAGING_BUFFER_SIZE).order(ByteOrder.LITTLE_ENDIAN));
    private static final ThreadLocal<int[][][]> BLOCK_GRID_CACHE = ThreadLocal.withInitial(() -> new int[16][16][16]);
    private static final ThreadLocal<int[][][]> LIGHT_GRID_CACHE = ThreadLocal.withInitial(() -> new int[16][16][16]);

    private boolean initialized;
    private volatile int meshesDispatched;
    private volatile int meshesCompletedGpu;
    private volatile int meshesCompletedCpu;
    private volatile int gpuFailures;

    public ComputeMesherCoordinator() {
    }

    public boolean initialize(long deviceHandle, PersistentBufferArena arena) {
        this.deviceHandle = deviceHandle;
        this.arena = arena;

        if (deviceHandle == 0L || arena == null) {
            MetalLogger.info("[ComputeMesher] Not available: invalid device or arena");
            this.available = false;
            this.initialized = false;
            return false;
        }

        try {
            available = NativeBridge.nInitComputeMesher(deviceHandle, MAX_VERTS_PER_SECTION);
            initialized = true;
            MetalLogger.info("[ComputeMesher] Initialized (GPU compute meshing: {})",
                    available ? "ACTIVE" : "CPU_FALLBACK");
            return available;
        } catch (UnsatisfiedLinkError e) {
            available = false;
            initialized = true;
            MetalLogger.info("[ComputeMesher] Native compute mesher not linked, using CPU fallback");
            return false;
        } catch (Throwable e) {
            available = false;
            initialized = true;
            MetalLogger.warn("[ComputeMesher] Init failed: {}, using CPU fallback", e.getMessage());
            return false;
        }
    }

    public boolean isAvailable() {
        return initialized && available;
    }

    public int meshOnGpu(PackedSectionData packed, int lodLevel) {
        if (!available || packed == null || packed.isEmpty()) {
            return -1;
        }

        if (lodLevel < 3) {
            return -1;
        }

        try {
            ByteBuffer staging = STAGING_CACHE.get();
            staging.clear();

            int[] blocks = packed.blockStates;
            for (int i = 0; i < 4096; i++) {
                staging.putInt(blocks[i]);
            }
            staging.put(packed.packedLight, 0, 4096);
            staging.flip();

            int maxOutputBytes = MAX_VERTS_PER_SECTION * COMPACT_VERTEX_STRIDE;
            int outputOffset = arena.allocate(maxOutputBytes);
            if (outputOffset < 0) {
                return -1;
            }

            int vertexCount = NativeBridge.nDispatchComputeMesh(
                    deviceHandle, staging, outputOffset,
                    packed.sectionX, packed.sectionY, packed.sectionZ,
                    MAX_VERTS_PER_SECTION);

            meshesDispatched++;

            if (vertexCount <= 0) {
                arena.free(outputOffset, maxOutputBytes);
                gpuFailures++;
                return -1;
            }

            int actualBytes = vertexCount * COMPACT_VERTEX_STRIDE;
            if (actualBytes < maxOutputBytes) {
                arena.free(outputOffset + actualBytes, maxOutputBytes - actualBytes);
            }

            meshesCompletedGpu++;
            return vertexCount;

        } catch (UnsatisfiedLinkError e) {
            available = false;
            MetalLogger.warn("[ComputeMesher] Dispatch not linked, disabling GPU meshing");
            return -1;
        } catch (Exception e) {
            gpuFailures++;
            MetalLogger.warn("[ComputeMesher] GPU mesh failed: {}", e.getMessage());
            return -1;
        }
    }

    public GreedyMesher.MeshResult meshSection(PackedSectionData packed, int lodLevel) {
        if (isAvailable() && lodLevel >= 3) {
            int gpuVertexCount = meshOnGpu(packed, lodLevel);
            if (gpuVertexCount > 0) {
                return null;
            }
        }

        GreedyMesher mesher = GreedyMesher.get();
        int[][][] blockGrid = BLOCK_GRID_CACHE.get();
        int[][][] lightGrid = LIGHT_GRID_CACHE.get();

        int[] blocks = packed.blockStates;
        byte[] lights = packed.packedLight;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int idx = (y << 8) | (z << 4) | x;
                    blockGrid[y][z][x] = blocks[idx];
                    lightGrid[y][z][x] = (lights[idx] >> 4) & 0xF;
                }
            }
        }

        GreedyMesher.MeshResult result = mesher.meshSection(blockGrid, null, lightGrid, null, null);
        if (result != null) {
            meshesCompletedCpu++;
        }
        return result;
    }

    public void destroy() {
        if (available && deviceHandle != 0L) {
            try {
                NativeBridge.nDestroyComputeMesher(deviceHandle);
            } catch (UnsatisfiedLinkError ignored) {
            }
        }
        initialized = false;
        available = false;
        MetalLogger.info("[ComputeMesher] Destroyed. GPU: {}/{} dispatched/completed, CPU: {}, GPU failures: {}",
                meshesDispatched, meshesCompletedGpu, meshesCompletedCpu, gpuFailures);
    }

    public int getMeshesDispatched() {
        return meshesDispatched;
    }

    public int getMeshesCompletedGpu() {
        return meshesCompletedGpu;
    }

    public int getMeshesCompletedCpu() {
        return meshesCompletedCpu;
    }

    public int getGpuFailures() {
        return gpuFailures;
    }

    public String getDebugInfo() {
        return String.format("ComputeMesh: gpu=%s dispatched=%d gpuOK=%d cpuOK=%d fail=%d",
                available ? "ON" : "OFF", meshesDispatched,
                meshesCompletedGpu, meshesCompletedCpu, gpuFailures);
    }
}
