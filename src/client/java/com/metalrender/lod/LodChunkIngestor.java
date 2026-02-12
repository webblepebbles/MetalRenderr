package com.metalrender.lod;

import com.metalrender.util.MetalLogger;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.LightType;
import net.minecraft.world.chunk.ChunkNibbleArray;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.chunk.light.LightingProvider;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class LodChunkIngestor {

    private static volatile LodChunkIngestor instance;

    private ExecutorService workerPool;

    private final BlockingQueue<IngestTask> taskQueue = new LinkedBlockingQueue<>(512);

    private LodChunkStorage storage;

    private final AtomicInteger ingestedCount = new AtomicInteger(0);
    private final AtomicInteger droppedCount = new AtomicInteger(0);
    private final AtomicLong totalProcessTimeNs = new AtomicLong(0);
    private final AtomicInteger pendingTasks = new AtomicInteger(0);

    private volatile boolean running = false;
    private static final int FAR_DISTANCE_SECTIONS = 32;

    private static final class IngestTask {
        final int chunkX, chunkZ;
        final int minSectionY;
        final int sectionCount;
        final int[][] blockIds;
        final byte[][] packedLight;
        final int distanceSections;

        IngestTask(int chunkX, int chunkZ, int minSectionY, int sectionCount,
                int[][] blockIds, byte[][] packedLight, int distanceSections) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.minSectionY = minSectionY;
            this.sectionCount = sectionCount;
            this.blockIds = blockIds;
            this.packedLight = packedLight;
            this.distanceSections = distanceSections;
        }
    }

    private LodChunkIngestor() {
    }

    public static LodChunkIngestor getInstance() {
        if (instance == null) {
            synchronized (LodChunkIngestor.class) {
                if (instance == null) {
                    instance = new LodChunkIngestor();
                }
            }
        }
        return instance;
    }

    public void initialize(LodChunkStorage storage) {
        if (running) {
            shutdown();
        }

        this.storage = storage;

        int threadCount = Math.max(2, Math.min(3,
                Runtime.getRuntime().availableProcessors() / 2 - 1));

        workerPool = Executors.newFixedThreadPool(threadCount, new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger(0);

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "MetalRender-LOD-Ingest-" + counter.getAndIncrement());
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            }
        });

        running = true;
        for (int i = 0; i < threadCount; i++) {
            workerPool.submit(this::workerLoop);
        }

        MetalLogger.info("[LodChunkIngestor] Started with {} worker threads", threadCount);
    }

    public void captureChunk(WorldChunk chunk) {
        if (!running || storage == null || chunk == null)
            return;

        try {
            int chunkX = chunk.getPos().x;
            int chunkZ = chunk.getPos().z;

            ChunkSection[] sections = chunk.getSectionArray();
            if (sections == null || sections.length == 0)
                return;

            int sectionCount = sections.length;
            int minSectionY = chunk.getBottomSectionCoord();
            MinecraftClient mc = MinecraftClient.getInstance();
            int distanceSections = FAR_DISTANCE_SECTIONS;
            if (mc != null && mc.player != null) {
                int camSX = mc.player.getBlockX() >> 4;
                int camSZ = mc.player.getBlockZ() >> 4;
                int dx = Math.abs(chunkX - camSX);
                int dz = Math.abs(chunkZ - camSZ);
                distanceSections = Math.max(dx, dz);
            }

            boolean isNear = distanceSections < FAR_DISTANCE_SECTIONS;

            int[][] blockIds = new int[sectionCount][];
            byte[][] packedLightData = new byte[sectionCount][];

            LightingProvider lightEngine = chunk.getWorld().getLightingProvider();

            for (int si = 0; si < sectionCount; si++) {
                ChunkSection section = sections[si];
                if (section == null || section.isEmpty()) {
                    blockIds[si] = null;
                    packedLightData[si] = null;
                    continue;
                }

                int[] sectionBlocks = new int[4096];
                int nonAir = 0;

                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            BlockState state = section.getBlockState(x, y, z);
                            int stateId = state.isAir() ? 0 : Block.getRawIdFromState(state);
                            sectionBlocks[(y << 8) | (z << 4) | x] = stateId;
                            if (stateId != 0)
                                nonAir++;
                        }
                    }
                }

                if (nonAir == 0) {
                    blockIds[si] = null;
                    packedLightData[si] = null;
                    continue;
                }

                blockIds[si] = sectionBlocks;
                if (isNear) {
                    byte[] lightData = new byte[4096];
                    int sectionY = minSectionY + si;
                    ChunkSectionPos sectionPos = ChunkSectionPos.from(chunkX, sectionY, chunkZ);

                    ChunkNibbleArray blockLight = lightEngine.get(LightType.BLOCK)
                            .getLightSection(sectionPos);
                    ChunkNibbleArray skyLight = lightEngine.get(LightType.SKY)
                            .getLightSection(sectionPos);

                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            for (int x = 0; x < 16; x++) {
                                int sky = (skyLight != null) ? skyLight.get(x, y, z) : 15;
                                int blk = (blockLight != null) ? blockLight.get(x, y, z) : 0;
                                lightData[(y << 8) | (z << 4) | x] = (byte) (((sky & 0xF) << 4) | (blk & 0xF));
                            }
                        }
                    }
                    packedLightData[si] = lightData;
                } else {
                    packedLightData[si] = null;
                }
            }

            IngestTask task = new IngestTask(chunkX, chunkZ, minSectionY,
                    sectionCount, blockIds, packedLightData, distanceSections);

            if (!taskQueue.offer(task)) {
                droppedCount.incrementAndGet();
            } else {
                pendingTasks.incrementAndGet();
            }

        } catch (Exception e) {
            MetalLogger.warn("[LodChunkIngestor] Failed to capture chunk: {}", e.getMessage());
        }
    }

    public void captureChunkAt(int chunkX, int chunkZ) {
        if (!running)
            return;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.world == null)
            return;

        WorldChunk chunk = mc.world.getChunk(chunkX, chunkZ);
        if (chunk != null) {
            captureChunk(chunk);
        }
    }

    private void workerLoop() {
        while (running) {
            try {
                IngestTask task = taskQueue.poll(100, TimeUnit.MILLISECONDS);
                if (task == null)
                    continue;

                processTask(task);
                pendingTasks.decrementAndGet();

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                MetalLogger.warn("[LodChunkIngestor] Worker error: {}", e.getMessage());
            }
        }
    }

    private void processTask(IngestTask task) {
        long startNs = System.nanoTime();

        for (int si = 0; si < task.sectionCount; si++) {
            if (task.blockIds[si] == null)
                continue;

            int sectionY = task.minSectionY + si;
            int sectionX = task.chunkX;
            int sectionZ = task.chunkZ;

            PackedSectionData packed = new PackedSectionData(sectionX, sectionY, sectionZ);
            System.arraycopy(task.blockIds[si], 0, packed.blockStates, 0, 4096);
            int nonAir = 0;
            for (int i = 0; i < 4096; i++) {
                if (packed.blockStates[i] != 0)
                    nonAir++;
            }
            packed.nonAirCount = nonAir;

            if (nonAir == 0)
                continue;
            if (task.packedLight[si] != null) {
                System.arraycopy(task.packedLight[si], 0, packed.packedLight, 0, 4096);
            } else {
                packed.computeDistantShadows();
            }
            packed.generateMips();

            GreedyMesher mesher = GreedyMesher.get();
            GreedyMesher.MeshResult result = meshFromPacked(packed, mesher);

            if (result == null)
                continue;

            LodSection section = new LodSection(sectionX, sectionY, sectionZ);
            section.setMeshData(
                    result.texturedVertices, result.texturedVertexCount,
                    result.compactVertices, result.compactVertexCount,
                    result.colorPalette, result.paletteSize,
                    result.boundsRadius);

            try {
                storage.putSectionWithKey(section);
            } catch (Exception e) {
                MetalLogger.warn("[LodChunkIngestor] Failed to store section ({},{},{}): {}",
                        sectionX, sectionY, sectionZ, e.getMessage());
            }
        }

        long elapsed = System.nanoTime() - startNs;
        totalProcessTimeNs.addAndGet(elapsed);
        ingestedCount.incrementAndGet();
    }

    private GreedyMesher.MeshResult meshFromPacked(PackedSectionData packed, GreedyMesher mesher) {
        int[][][] blockGrid = new int[16][16][16];
        int[][][] lightGrid = new int[16][16][16];

        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int idx = (y << 8) | (z << 4) | x;
                    blockGrid[y][z][x] = packed.blockStates[idx];
                    lightGrid[y][z][x] = (packed.packedLight[idx] >> 4) & 0xF;
                }
            }
        }

        return mesher.meshSection(blockGrid, null, lightGrid, null, null);
    }

    public void shutdown() {
        running = false;

        if (workerPool != null) {
            workerPool.shutdown();
            try {
                if (!workerPool.awaitTermination(5, TimeUnit.SECONDS)) {
                    workerPool.shutdownNow();
                }
            } catch (InterruptedException e) {
                workerPool.shutdownNow();
                Thread.currentThread().interrupt();
            }
            workerPool = null;
        }

        taskQueue.clear();

        MetalLogger.info("[LodChunkIngestor] Shutdown. Ingested {} chunks, dropped {}, " +
                "avg time: {}ms",
                ingestedCount.get(), droppedCount.get(),
                String.format("%.2f", ingestedCount.get() > 0
                        ? (totalProcessTimeNs.get() / (double) ingestedCount.get()) / 1_000_000.0
                        : 0.0));
    }

    public boolean isRunning() {
        return running;
    }

    public int getIngestedCount() {
        return ingestedCount.get();
    }

    public int getDroppedCount() {
        return droppedCount.get();
    }

    public int getPendingCount() {
        return pendingTasks.get();
    }

    public double getAverageMeshTimeMs() {
        int count = ingestedCount.get();
        if (count == 0)
            return 0.0;
        return (totalProcessTimeNs.get() / (double) count) / 1_000_000.0;
    }
}
