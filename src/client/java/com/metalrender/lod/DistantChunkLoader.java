package com.metalrender.lod;

import com.metalrender.util.MetalLogger;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.chunk.PaletteProvider;
import net.minecraft.world.chunk.PalettedContainer;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

public final class DistantChunkLoader {

    private static volatile DistantChunkLoader instance;

    private ExecutorService workerPool;
    private LodChunkStorage storage;
    private volatile boolean running = false;

    private final BlockingQueue<long[]> requestQueue = new LinkedBlockingQueue<>(2048);
    private final ConcurrentHashMap<Long, Boolean> pendingSections = new ConcurrentHashMap<>();

    private final AtomicInteger loadedCount = new AtomicInteger(0);
    private final AtomicInteger failedCount = new AtomicInteger(0);
    private final AtomicLong totalLoadTimeNs = new AtomicLong(0);

    private static final int BATCH_SIZE = 16;

    private DistantChunkLoader() {
    }

    public static DistantChunkLoader getInstance() {
        if (instance == null) {
            synchronized (DistantChunkLoader.class) {
                if (instance == null) {
                    instance = new DistantChunkLoader();
                }
            }
        }
        return instance;
    }

    public void initialize(LodChunkStorage storage) {
        if (running)
            shutdown();
        this.storage = storage;

        int threadCount = Math.max(1, Math.min(2,
                Runtime.getRuntime().availableProcessors() / 4));

        workerPool = Executors.newFixedThreadPool(threadCount, r -> {
            Thread t = new Thread(r, "MetalRender-DistantChunk-Loader");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });

        running = true;
        for (int i = 0; i < threadCount; i++) {
            workerPool.submit(this::workerLoop);
        }

        MetalLogger.info("[DistantChunkLoader] Started with {} worker threads", threadCount);
    }

    public void requestSections(long[] sectionKeys) {
        if (!running || storage == null)
            return;

        long[] batch = new long[Math.min(sectionKeys.length, BATCH_SIZE)];
        int count = 0;

        for (long key : sectionKeys) {
            if (storage.getSection(key) != null)
                continue;
            if (pendingSections.containsKey(key))
                continue;

            pendingSections.put(key, Boolean.TRUE);
            batch[count++] = key;

            if (count >= BATCH_SIZE) {
                long[] copy = new long[count];
                System.arraycopy(batch, 0, copy, 0, count);
                requestQueue.offer(copy);
                count = 0;
            }
        }

        if (count > 0) {
            long[] copy = new long[count];
            System.arraycopy(batch, 0, copy, 0, count);
            requestQueue.offer(copy);
        }
    }

    private void workerLoop() {
        GreedyMesher mesher = GreedyMesher.get();

        while (running) {
            try {
                long[] batch = requestQueue.poll(200, TimeUnit.MILLISECONDS);
                if (batch == null)
                    continue;

                for (long key : batch) {
                    if (!running)
                        break;
                    try {
                        loadSection(key, mesher);
                    } finally {
                        pendingSections.remove(key);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                MetalLogger.warn("[DistantChunkLoader] Worker error: {}", e.getMessage());
            }
        }
    }

    private void loadSection(long sectionKey, GreedyMesher mesher) {
        long startNs = System.nanoTime();

        int sx = LodSection.unpackX(sectionKey);
        int sy = LodSection.unpackY(sectionKey);
        int sz = LodSection.unpackZ(sectionKey);

        if (storage.getSection(sectionKey) != null) {
            return;
        }

        PackedSectionData packed = tryLoadFromRegionFile(sx, sz, sy);

        if (packed == null || packed.isEmpty()) {
            failedCount.incrementAndGet();
            return;
        }

        packed.computeDistantShadows();
        packed.generateMips();

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

        GreedyMesher.MeshResult result = mesher.meshSection(
                blockGrid, null, lightGrid, null, null);

        if (result == null) {
            failedCount.incrementAndGet();
            return;
        }

        LodSection section = new LodSection(sx, sy, sz);
        section.setMeshData(
                result.texturedVertices, result.texturedVertexCount,
                result.compactVertices, result.compactVertexCount,
                result.colorPalette, result.paletteSize,
                result.boundsRadius);

        try {
            storage.putSectionWithKey(section);
            loadedCount.incrementAndGet();
        } catch (Exception e) {
            failedCount.incrementAndGet();
        }

        totalLoadTimeNs.addAndGet(System.nanoTime() - startNs);
    }

    private PackedSectionData tryLoadFromRegionFile(int sectionX, int sectionZ, int sectionY) {
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc == null || mc.getServer() == null)
                return null;

            Path worldDir = mc.getServer().getSavePath(net.minecraft.util.WorldSavePath.ROOT);
            if (worldDir == null)
                return null;

            int regionX = sectionX >> 5;
            int regionZ = sectionZ >> 5;
            Path regionFile = worldDir.resolve("region")
                    .resolve("r." + regionX + "." + regionZ + ".mca");

            if (!Files.exists(regionFile))
                return null;

            try (FileChannel channel = FileChannel.open(regionFile, StandardOpenOption.READ)) {
                long fileSize = channel.size();
                if (fileSize < 8192)
                    return null;

                ByteBuffer headerBuf = ByteBuffer.allocate(4096).order(ByteOrder.BIG_ENDIAN);
                channel.read(headerBuf, 0);
                headerBuf.flip();

                int localX = sectionX & 31;
                int localZ = sectionZ & 31;
                int chunkIdx = localX + localZ * 32;

                int locationEntry = headerBuf.getInt(chunkIdx * 4);
                if (locationEntry == 0)
                    return null;

                int sectorStart = (locationEntry >> 8) & 0xFFFFFF;
                int sectorCount = locationEntry & 0xFF;
                if (sectorCount == 0)
                    return null;

                long dataOffset = (long) sectorStart * 4096;
                if (dataOffset + 5 > fileSize)
                    return null;

                ByteBuffer chunkHeader = ByteBuffer.allocate(5).order(ByteOrder.BIG_ENDIAN);
                channel.read(chunkHeader, dataOffset);
                chunkHeader.flip();

                int chunkLength = chunkHeader.getInt();
                byte compressionType = chunkHeader.get();
                if (chunkLength <= 1)
                    return null;

                int compressedLength = chunkLength - 1;

                ByteBuffer compressedBuf = ByteBuffer.allocate(compressedLength);
                channel.read(compressedBuf, dataOffset + 5);
                compressedBuf.flip();

                byte[] compressedData = new byte[compressedLength];
                compressedBuf.get(compressedData);

                InputStream decompressed;
                switch (compressionType) {
                    case 1:
                        decompressed = new GZIPInputStream(new ByteArrayInputStream(compressedData));
                        break;
                    case 2:
                        decompressed = new InflaterInputStream(new ByteArrayInputStream(compressedData));
                        break;
                    case 3:
                        decompressed = new ByteArrayInputStream(compressedData);
                        break;
                    default:
                        return null;
                }

                NbtCompound chunkNbt;
                try (DataInputStream dis = new DataInputStream(new BufferedInputStream(decompressed))) {
                    chunkNbt = NbtIo.readCompound(dis);
                }

                if (chunkNbt == null)
                    return null;

                String status = chunkNbt.getString("Status").orElse(null);
                if (status == null)
                    return null;
                if (!status.equals("minecraft:full") && !status.equals("full"))
                    return null;

                NbtList sectionsList = chunkNbt.getList("sections").orElse(null);
                if (sectionsList == null)
                    return null;

                for (int si = 0; si < sectionsList.size(); si++) {
                    NbtCompound sectionNbt = sectionsList.getCompound(si).orElse(null);
                    if (sectionNbt == null)
                        continue;

                    int nbtY = sectionNbt.getByte("Y").orElse((byte) 0);
                    if (nbtY != sectionY)
                        continue;

                    NbtCompound blockStatesNbt = sectionNbt.getCompound("block_states").orElse(null);
                    if (blockStatesNbt == null || blockStatesNbt.isEmpty())
                        return null;

                    PalettedContainer<BlockState> blockStates = PalettedContainer.createPalettedContainerCodec(
                            BlockState.CODEC,
                            PaletteProvider.forBlockStates(Block.STATE_IDS),
                            net.minecraft.block.Blocks.AIR.getDefaultState()).parse(NbtOps.INSTANCE, blockStatesNbt)
                            .result().orElse(null);

                    if (blockStates == null)
                        return null;

                    PackedSectionData packed = new PackedSectionData(sectionX, sectionY, sectionZ);
                    int nonAir = 0;

                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            for (int x = 0; x < 16; x++) {
                                BlockState state = blockStates.get(x, y, z);
                                int stateId = state.isAir() ? 0 : Block.getRawIdFromState(state);
                                packed.blockStates[(y << 8) | (z << 4) | x] = stateId;
                                if (stateId != 0)
                                    nonAir++;
                            }
                        }
                    }

                    packed.nonAirCount = nonAir;
                    return packed;
                }
            }
        } catch (Exception e) {
        }
        return null;
    }

    public void shutdown() {
        running = false;

        if (workerPool != null) {
            workerPool.shutdown();
            try {
                if (!workerPool.awaitTermination(3, TimeUnit.SECONDS)) {
                    workerPool.shutdownNow();
                }
            } catch (InterruptedException e) {
                workerPool.shutdownNow();
                Thread.currentThread().interrupt();
            }
            workerPool = null;
        }

        requestQueue.clear();
        pendingSections.clear();

        MetalLogger.info("[DistantChunkLoader] Shutdown. Loaded: {}, failed: {}, avg: {}ms",
                loadedCount.get(), failedCount.get(),
                String.format("%.2f", loadedCount.get() > 0
                        ? (totalLoadTimeNs.get() / (double) loadedCount.get()) / 1_000_000.0
                        : 0.0));
    }

    public boolean isRunning() {
        return running;
    }

    public int getLoadedCount() {
        return loadedCount.get();
    }

    public int getFailedCount() {
        return failedCount.get();
    }

    public int getPendingCount() {
        return pendingSections.size();
    }
}
