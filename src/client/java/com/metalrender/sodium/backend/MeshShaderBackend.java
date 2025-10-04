package com.metalrender.sodium.backend;

import com.metalrender.nativebridge.MeshShaderNative;
import com.metalrender.nativebridge.NativeBridge;
import com.metalrender.config.MetalRenderConfig;
import com.metalrender.util.MetalLogger;
import com.metalrender.util.FrustumCuller;
import com.metalrender.util.OcclusionCuller;
import com.metalrender.util.PerformanceLogger;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.util.math.BlockPos;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Set;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class MeshShaderBackend {
    private int debugFrameCount = 0;
    private final ExecutorService meshUploadExecutor = Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors() / 2), r -> {
                Thread t = new Thread(r, "MeshShaderUploadThread");
                t.setDaemon(true);
                return t;
            });
    private final Queue<DrawRequest> drawQueue = new ConcurrentLinkedQueue<>();
    private final java.util.List<BatchDrawCommand> drawBatch = new java.util.ArrayList<>();
    private static final int BATCH_SIZE_LIMIT = 16;

    private static class DrawRequest {
        final BlockPos pos;
        final int layer;

        DrawRequest(BlockPos pos, int layer) {
            this.pos = pos;
            this.layer = layer;
        }
    }

    private static class BatchDrawCommand {
        final long handle;
        final int layer;

        BatchDrawCommand(long handle, int layer) {
            this.handle = handle;
            this.layer = layer;
        }
    }

    private long deviceHandle = 0L;
    private boolean initialized = false;
    private boolean meshEnabled = false;
    private final Map<BlockPos, Long> chunkHandles = new java.util.concurrent.ConcurrentHashMap<>();
    private final Set<BlockPos> meshed = java.util.Collections
            .newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
    private final java.util.concurrent.ConcurrentLinkedQueue<Long> meshPool = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final java.util.concurrent.atomic.AtomicInteger poolSize = new java.util.concurrent.atomic.AtomicInteger(0);
    private final int MAX_POOL_SIZE = Math.max(8, Runtime.getRuntime().availableProcessors() * 4);
    private volatile boolean nativeUpdateSupported = false;
    private volatile boolean nativeUpdateProbed = false;
    private final FrustumCuller frustumCuller = new FrustumCuller();
    private final OcclusionCuller occlusionCuller = new OcclusionCuller();
    private final PerformanceLogger perfLogger = new PerformanceLogger();
    private final com.metalrender.util.LODManager lodManager = new com.metalrender.util.LODManager();
    private final com.metalrender.util.SpatialCullingCache spatialCache = new com.metalrender.util.SpatialCullingCache();

    public synchronized boolean initIfNeeded() {
        if (initialized) {
            MetalLogger.info("MeshShaderBackend already initialized");
            return true;
        }

        MetalLogger.info("MeshShaderBackend initialising");
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getWindow() == null) {
            MetalLogger.error("MeshShaderBackend missing MinecraftClient or Window");
            return false;
        }
        long ctx = org.lwjgl.glfw.GLFW.glfwGetCurrentContext();
        MetalLogger.info("GLFW context=" + ctx);
        if (ctx == 0L) {
            MetalLogger.error("GLFW context not ready");
            return false;
        }
        if (!NativeBridge.load()) {
            MetalLogger.error("NativeBridge.load() failed");
            return false;
        }
        long glfwWindow = client.getWindow().getHandle();
        MetalLogger.info("glfwWindow handle=" + glfwWindow);
        long nsWindow = org.lwjgl.glfw.GLFWNativeCocoa.glfwGetCocoaWindow(glfwWindow);
        MetalLogger.info("nsWindow=" + nsWindow);
        boolean srgb = true;
        deviceHandle = MeshShaderNative.initMeshDevice(nsWindow, srgb);
        MetalLogger.info("initMeshDevice returned deviceHandle=" + deviceHandle);
        if (deviceHandle == 0L) {
            MetalLogger.error("initMeshDevice returned 0");
            return false;
        }

        try {
            java.nio.file.Path tempDir = java.nio.file.Files.createTempDirectory("metalrender-shaders");
            tempDir.toFile().deleteOnExit();
            java.nio.file.Path out = tempDir.resolve("shaders.metallib");
            try (java.io.InputStream in = MeshShaderBackend.class.getClassLoader()
                    .getResourceAsStream("shaders.metallib")) {
                if (in != null) {
                    java.nio.file.Files.copy(in, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    MeshShaderNative.setShadersPath(out.toAbsolutePath().toString());
                    MetalLogger.info("Provided shaders.metallib to native at: " + out);
                } else {
                    MetalLogger.warn("shaders.metallib not found in resources; native will try default library");
                }
            }
        } catch (Throwable t) {
            MetalLogger.warn("Unable to provision shaders.metallib: " + t);
        }
        try {
            boolean configEnabled = MetalRenderConfig.get().getAdvancedMetalFeatures().isMeshShadersEnabled();
            MetalLogger.info("Config mesh shaders enabled: " + configEnabled);
            if (configEnabled) {
                boolean nativeSupport = MeshShaderNative.supportsMeshShaders(deviceHandle);
                MetalLogger.info("Native mesh shaders supported: " + nativeSupport);
                meshEnabled = nativeSupport;
            } else {
                meshEnabled = false;
            }
            MetalLogger.info("meshEnabled=" + meshEnabled);
        } catch (Throwable t) {
            MetalLogger.error("error probing mesh support: " + t);
            meshEnabled = false;
        }
        if (meshEnabled) {
            try {
                MeshShaderNative.initMeshPipeline(deviceHandle);
                MetalLogger.info("Mesh pipeline initialized");
                if (!nativeUpdateProbed) {
                    nativeUpdateProbed = true;
                    nativeUpdateSupported = true;
                    MetalLogger.info("nativeUpdateSupported assumed true (reflection removed)");
                }
            } catch (Throwable t) {
                MetalLogger.error("initMeshPipeline failed: " + t);
                meshEnabled = false;
            }
        }
        initialized = true;
        MetalLogger.info("MeshShaderBackend initialized, meshEnabled=" + meshEnabled);
        return true;
    }

    public synchronized void destroy() {
        if (!initialized)
            return;

        meshUploadExecutor.shutdown();
        try {
            if (!meshUploadExecutor.awaitTermination(100, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                meshUploadExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            meshUploadExecutor.shutdownNow();
        }

        for (Long h : chunkHandles.values()) {
            if (h != null && h != 0L) {
                try {
                    MeshShaderNative.destroyNativeChunkMesh(h);
                } catch (Throwable ignored) {
                }
            }
        }
        chunkHandles.clear();
        meshed.clear();
        Long ph;
        while ((ph = meshPool.poll()) != null) {
            try {
                MeshShaderNative.destroyNativeChunkMesh(ph);
            } catch (Throwable ignored) {
            }
            poolSize.decrementAndGet();
        }
        if (deviceHandle != 0L) {
            try {
                MeshShaderNative.destroyMeshDevice(deviceHandle);
            } catch (Throwable t) {
                MetalLogger.error("Error destroying mesh device");
            }
            deviceHandle = 0L;
        }
        initialized = false;
    }

    public Future<Long> uploadChunkMeshAsync(BlockPos pos, ByteBuffer vertexData, int vertexCount, int vertexStride,
            ByteBuffer indexData, int indexCount, int indexType) {
        return meshUploadExecutor.submit(() -> uploadChunkMeshInternal(pos, vertexData, vertexCount, vertexStride,
                indexData, indexCount, indexType));
    }

    private synchronized long uploadChunkMeshInternal(BlockPos pos, ByteBuffer vertexData, int vertexCount,
            int vertexStride,
            ByteBuffer indexData, int indexCount, int indexType) {
        if (!initialized)
            return 0L;
        if (!meshEnabled)
            return 0L;
        if (vertexData == null || !vertexData.isDirect() || vertexCount <= 0) {
            return 0L;
        }
        try {
            long h = 0L;
            if (nativeUpdateSupported) {
                Long pooled = meshPool.poll();
                if (pooled != null && pooled != 0L) {
                    try {
                        h = MeshShaderNative.updateNativeChunkMesh(pooled, deviceHandle, vertexData, vertexCount,
                                vertexStride, indexData, indexCount, indexType);
                    } catch (Throwable t) {
                        try {
                            MeshShaderNative.destroyNativeChunkMesh(pooled);
                        } catch (Throwable ignored) {
                        }
                        poolSize.decrementAndGet();
                        h = 0L;
                    }
                }
            }
            if (h == 0L) {
                h = MeshShaderNative.createNativeChunkMesh(deviceHandle, vertexData, vertexCount, vertexStride,
                        indexData, indexCount, indexType);
            }
            if (h != 0L) {
                chunkHandles.put(pos, h);
                meshed.add(pos);
                return h;
            } else {
                return 0L;
            }
        } catch (Throwable t) {
            return 0L;
        }
    }

    public long uploadChunkMesh(BlockPos pos, ByteBuffer vertexData, int vertexCount, int vertexStride,
            ByteBuffer indexData, int indexCount, int indexType) {
        try {
            return uploadChunkMeshAsync(pos, vertexData, vertexCount, vertexStride, indexData, indexCount, indexType)
                    .get();
        } catch (Exception e) {
            return 0L;
        }
    }

    public synchronized void removeChunkMesh(BlockPos pos) {
        Long h = chunkHandles.remove(pos);
        if (h != null && h != 0L) {
            if (poolSize.get() < MAX_POOL_SIZE) {
                meshPool.offer(h);
                poolSize.incrementAndGet();
            } else {
                try {
                    MeshShaderNative.destroyNativeChunkMesh(h);
                } catch (Throwable ignored) {
                }
            }
        }
        meshed.remove(pos);
    }

    public void beginFrame() {
        if (!initialized || !meshEnabled)
            return;
        try {
            MeshShaderNative.startMeshFrame(deviceHandle);

            MinecraftClient client = MinecraftClient.getInstance();
            if (client != null && client.gameRenderer != null) {
                Camera cam = client.gameRenderer.getCamera();
                if (cam != null) {
                    float fov = client.options.getFov().getValue().floatValue();
                    float aspect = (float) client.getWindow().getFramebufferWidth()
                            / Math.max(1, client.getWindow().getFramebufferHeight());
                    frustumCuller.updateFrustum(cam, fov, aspect, 0.1f, 256.0f);
                }
            }
        } catch (Throwable t) {
        }
    }

    public void queueDrawChunkLayer(BlockPos pos, int layer) {
        drawQueue.add(new DrawRequest(pos, layer));
    }

    public void processChunksAroundCamera(Camera camera, MinecraftClient client) {
        if (!initialized || !meshEnabled || camera == null || client == null || client.world == null) {
            return;
        }

        BlockPos cameraPos = camera.getBlockPos();
        int cameraChunkX = cameraPos.getX() >> 4;
        int cameraChunkZ = cameraPos.getZ() >> 4;
        int renderDistance = Math.min(16, Math.max(4, client.options.getViewDistance().getValue()));

        int chunksProcessed = 0;
        int chunksBuilt = 0;
        int chunksQueued = 0;

        perfLogger.startFrame();

        int maxChunksPerFrame = 64;
        int processedThisFrame = 0;

        for (int radius = 0; radius <= Math.min(renderDistance, 8)
                && processedThisFrame < maxChunksPerFrame; radius++) {
            for (int dx = -radius; dx <= radius && processedThisFrame < maxChunksPerFrame; dx++) {
                for (int dz = -radius; dz <= radius && processedThisFrame < maxChunksPerFrame; dz++) {
                    if (Math.abs(dx) != radius && Math.abs(dz) != radius && radius > 0) {
                        continue;
                    }

                    int chunkX = cameraChunkX + dx;
                    int chunkZ = cameraChunkZ + dz;
                    BlockPos chunkPos = new BlockPos(chunkX << 4, cameraPos.getY(), chunkZ << 4);

                    chunksProcessed++;
                    try {
                        if (!client.world.isChunkLoaded(chunkX, chunkZ)) {
                            continue;
                        }

                        if (!hasMesh(chunkPos)) {
                            if (buildChunkMesh(client.world.getChunk(chunkX, chunkZ), chunkPos)) {
                                chunksBuilt++;
                                processedThisFrame++;
                            }
                        }

                        if (hasMesh(chunkPos)) {
                            queueDrawChunkLayer(chunkPos, 0);
                            chunksQueued++;
                        }
                    } catch (Exception e) {
                        continue;
                    }
                }
            }
        }

        if (debugFrameCount <= 10 || debugFrameCount % 60 == 0) {
            MetalLogger.info("[CHUNK] Processed=" + chunksProcessed + ", Built=" + chunksBuilt +
                    ", Queued=" + chunksQueued + ", Camera=(" + cameraChunkX + "," + cameraChunkZ + ")");
        }
    }

    private boolean buildChunkMesh(net.minecraft.world.chunk.WorldChunk chunk, BlockPos chunkPos) {
        if (chunk == null)
            return false;

        if (hasMesh(chunkPos)) {
            return true;
        }

        try {

            boolean hasBlocks = false;
            int minY = chunk.getBottomY();
            int maxY = Math.min(chunk.getBottomY() + chunk.getHeight(), minY + 32);

            outerLoop: for (int x = 0; x < 16; x += 4) {
                for (int y = minY; y < maxY; y += 4) {
                    for (int z = 0; z < 16; z += 4) {
                        BlockPos pos = new BlockPos(chunk.getPos().getStartX() + x, y, chunk.getPos().getStartZ() + z);
                        if (!chunk.getBlockState(pos).isAir()) {
                            hasBlocks = true;
                            break outerLoop;
                        }
                    }
                }
            }

            meshed.add(chunkPos);

            if (!hasBlocks) {
                return true;
            }
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocateDirect(4 * (3 * 2 + 4));
            buf.order(java.nio.ByteOrder.nativeOrder());
            int groundY = Math.max(minY, 64);
            buf.putShort((short) 0).putShort((short) groundY).putShort((short) 0).putInt(0xFF00FF00);
            buf.putShort((short) 15).putShort((short) groundY).putShort((short) 0).putInt(0xFF00FF00);
            buf.putShort((short) 15).putShort((short) groundY).putShort((short) 15).putInt(0xFF00FF00);
            buf.putShort((short) 0).putShort((short) groundY).putShort((short) 15).putInt(0xFF00FF00);
            buf.rewind();

            long handle = uploadChunkMeshInternal(chunkPos, buf, 4, 3 * 2 + 4, null, 0, 0);
            if (handle != 0L) {
                chunkHandles.put(chunkPos, handle);
                return true;
            }

            return false;

        } catch (Exception e) {

            meshed.add(chunkPos);
            MetalLogger.warn("Failed to build mesh for chunk " + chunkPos + ": " + e.getMessage());
            return false;
        }
    }

    private void flushDrawBatch() {
        if (drawBatch.isEmpty())
            return;

        java.util.Map<Integer, java.util.List<Long>> layerBatches = new java.util.HashMap<>();
        for (BatchDrawCommand cmd : drawBatch) {
            layerBatches.computeIfAbsent(cmd.layer, k -> new java.util.ArrayList<>()).add(cmd.handle);
        }

        try {
            for (java.util.Map.Entry<Integer, java.util.List<Long>> entry : layerBatches.entrySet()) {
                int layer = entry.getKey();
                java.util.List<Long> handles = entry.getValue();
                for (Long handle : handles) {
                    MeshShaderNative.drawNativeChunkMesh(deviceHandle, handle, layer);
                }
            }
        } catch (Throwable t) {
        }
        drawBatch.clear();
    }

    public void processDrawQueue() {
        if (!initialized || !meshEnabled)
            return;

        int processed = 0;
        int frustumCulled = 0;
        int occlusionCulled = 0;
        int sectorCulled = 0;
        int lodCulled = 0;
        int noMeshSkipped = 0;

        MinecraftClient client = MinecraftClient.getInstance();
        Camera camera = client != null && client.gameRenderer != null ? client.gameRenderer.getCamera() : null;

        DrawRequest req;
        while ((req = drawQueue.poll()) != null) {
            Long h = chunkHandles.get(req.pos);
            if (h == null || h == 0L) {
                noMeshSkipped++;
                continue;
            }

            int chunkX = req.pos.getX() >> 4;
            int chunkZ = req.pos.getZ() >> 4;
            int minY = req.pos.getY() - 8;
            int maxY = req.pos.getY() + 24;

            int sectorX = chunkX >> 2;
            int sectorZ = chunkZ >> 2;

            if (!frustumCuller.isSectorVisible(sectorX, sectorZ, minY, maxY)) {
                sectorCulled++;
                continue;
            }

            if (!frustumCuller.isChunkVisible(chunkX, chunkZ, minY, maxY)) {
                frustumCulled++;
                continue;
            }

            if (camera != null && !lodManager.shouldRenderChunk(req.pos, camera)) {
                lodCulled++;
                continue;
            }

            if (camera != null && occlusionCuller.isChunkOccluded(req.pos, camera)) {
                occlusionCulled++;
                continue;
            }

            drawBatch.add(new BatchDrawCommand(h, req.layer));
            processed++;

            if (drawBatch.size() >= BATCH_SIZE_LIMIT) {
                flushDrawBatch();
            }
        }

        flushDrawBatch();

        debugFrameCount++;
        int totalQueued = processed + frustumCulled + occlusionCulled + sectorCulled + lodCulled + noMeshSkipped;

        double currentFPS = perfLogger.getCurrentFPS();
        double frameTime = perfLogger.getAvgFrameTime();
        lodManager.updatePerformanceMetrics(currentFPS, (long) frameTime);

        perfLogger.endFrame(totalQueued, processed, frustumCulled + sectorCulled, occlusionCulled);

        if (debugFrameCount <= 10) {
            MetalLogger.info("[DEBUG] Frame " + debugFrameCount + ": Queued=" + totalQueued + ", Drawn=" + processed
                    + ", FCull=" + frustumCulled + ", SCull=" + sectorCulled + ", LOD=" + lodCulled + ", OCull="
                    + occlusionCulled
                    + ", NoMesh=" + noMeshSkipped);
        } else if (debugFrameCount % 60 == 0) {
            MetalLogger.info("[RENDER] Queued=" + totalQueued + ", Drawn=" + processed + ", FCull=" + frustumCulled
                    + ", SCull=" + sectorCulled + ", LOD=" + lodCulled + ", OCull=" + occlusionCulled
                    + ", RenderDist=" + lodManager.getCurrentRenderDistance() + ", Cache=" + chunkHandles.size()
                    + ", SpatialCache=" + spatialCache.getCacheSize());
        }
    }

    public void endFrame() {
        if (!initialized || !meshEnabled)
            return;
        try {
            MeshShaderNative.endMeshFrame(deviceHandle);
        } catch (Throwable t) {
        }
    }

    public boolean isMeshEnabled() {
        return meshEnabled;
    }

    public boolean hasMesh(BlockPos pos) {
        return meshed.contains(pos);
    }
}
