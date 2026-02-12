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

public final class RegionFileImporter {

    private static volatile RegionFileImporter instance;

    private ExecutorService workerPool;
    private LodChunkStorage storage;
    private volatile boolean running = false;
    private volatile boolean importing = false;

    private final BlockingQueue<Path> regionQueue = new LinkedBlockingQueue<>(256);

    private final AtomicInteger regionsProcessed = new AtomicInteger(0);
    private final AtomicInteger chunksImported = new AtomicInteger(0);
    private final AtomicInteger sectionsImported = new AtomicInteger(0);
    private final AtomicInteger errors = new AtomicInteger(0);
    private final AtomicLong totalTimeNs = new AtomicLong(0);

    private static final int COMPRESSION_GZIP = 1;
    private static final int COMPRESSION_DEFLATE = 2;
    private static final int COMPRESSION_NONE = 3;

    private RegionFileImporter() {
    }

    public static RegionFileImporter getInstance() {
        if (instance == null) {
            synchronized (RegionFileImporter.class) {
                if (instance == null) {
                    instance = new RegionFileImporter();
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
            Thread t = new Thread(r, "MetalRender-RegionImporter");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });

        running = true;
        for (int i = 0; i < threadCount; i++) {
            workerPool.submit(this::workerLoop);
        }

        MetalLogger.info("[RegionFileImporter] Started with {} worker threads", threadCount);
    }

    public void startImport() {
        if (!running || importing)
            return;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.getServer() == null) {
            MetalLogger.info("[RegionFileImporter] Not singleplayer, skipping import");
            return;
        }

        Path worldDir = mc.getServer().getSavePath(net.minecraft.util.WorldSavePath.ROOT);
        if (worldDir == null)
            return;

        Path regionDir = worldDir.resolve("region");

        if (!Files.isDirectory(regionDir)) {
            MetalLogger.warn("[RegionFileImporter] Region directory not found: {}", regionDir);
            return;
        }

        importing = true;
        regionsProcessed.set(0);
        chunksImported.set(0);
        sectionsImported.set(0);
        errors.set(0);
        totalTimeNs.set(0);

        try {
            Files.list(regionDir)
                    .filter(p -> p.getFileName().toString().endsWith(".mca"))
                    .forEach(p -> {
                        if (!regionQueue.offer(p)) {
                            MetalLogger.warn("[RegionFileImporter] Queue full, skipping {}", p.getFileName());
                        }
                    });
            MetalLogger.info("[RegionFileImporter] Queued {} region files from {}",
                    regionQueue.size(), regionDir);
        } catch (IOException e) {
            MetalLogger.error("[RegionFileImporter] Failed to enumerate region files: {}", e.getMessage());
            importing = false;
        }
    }

    private void workerLoop() {
        GreedyMesher mesher = GreedyMesher.get();

        while (running) {
            try {
                Path regionPath = regionQueue.poll(200, TimeUnit.MILLISECONDS);
                if (regionPath == null) {
                    if (importing && regionQueue.isEmpty()) {
                        importing = false;
                        MetalLogger.info("[RegionFileImporter] Import complete. Regions: {}, Chunks: {}, " +
                                "Sections: {}, Errors: {}",
                                regionsProcessed.get(), chunksImported.get(),
                                sectionsImported.get(), errors.get());
                    }
                    continue;
                }

                processRegionFile(regionPath, mesher);
                regionsProcessed.incrementAndGet();

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                MetalLogger.warn("[RegionFileImporter] Worker error: {}", e.getMessage());
                errors.incrementAndGet();
            }
        }
    }

    private void processRegionFile(Path regionPath, GreedyMesher mesher) {
        long startNs = System.nanoTime();
        String filename = regionPath.getFileName().toString();

        String[] parts = filename.split("\\.");
        if (parts.length != 4 || !parts[0].equals("r") || !parts[3].equals("mca")) {
            MetalLogger.warn("[RegionFileImporter] Invalid region filename: {}", filename);
            return;
        }

        int regionX, regionZ;
        try {
            regionX = Integer.parseInt(parts[1]);
            regionZ = Integer.parseInt(parts[2]);
        } catch (NumberFormatException e) {
            MetalLogger.warn("[RegionFileImporter] Invalid region coords in: {}", filename);
            return;
        }

        try (FileChannel channel = FileChannel.open(regionPath, StandardOpenOption.READ)) {
            long fileSize = channel.size();
            if (fileSize < 8192) {
                return;
            }

            ByteBuffer fileBuf = ByteBuffer.allocate((int) fileSize).order(ByteOrder.BIG_ENDIAN);
            channel.read(fileBuf);
            fileBuf.flip();

            for (int idx = 0; idx < 1024; idx++) {
                int locationEntry = fileBuf.getInt(idx * 4);
                if (locationEntry == 0)
                    continue;

                int sectorStart = (locationEntry >> 8) & 0xFFFFFF;
                int sectorCount = locationEntry & 0xFF;
                if (sectorCount == 0)
                    continue;

                long dataStart = (long) sectorStart * 4096;
                if (dataStart + 5 > fileSize)
                    continue;

                try {
                    int chunkX = (regionX << 5) + (idx & 31);
                    int chunkZ = (regionZ << 5) + (idx >> 5);

                    processChunkSector(fileBuf, (int) dataStart, chunkX, chunkZ, mesher);
                    chunksImported.incrementAndGet();
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            }
        } catch (IOException e) {
            MetalLogger.warn("[RegionFileImporter] Failed to read {}: {}", filename, e.getMessage());
            errors.incrementAndGet();
        }

        totalTimeNs.addAndGet(System.nanoTime() - startNs);
    }

    private void processChunkSector(ByteBuffer fileBuf, int sectorOffset,
            int chunkX, int chunkZ, GreedyMesher mesher) throws Exception {
        int chunkLength = fileBuf.getInt(sectorOffset);
        byte compressionType = fileBuf.get(sectorOffset + 4);

        if (chunkLength <= 1)
            return;
        int compressedLength = chunkLength - 1;

        byte[] compressedData = new byte[compressedLength];
        fileBuf.position(sectorOffset + 5);
        fileBuf.get(compressedData);

        InputStream decompressed;
        switch (compressionType) {
            case COMPRESSION_GZIP:
                decompressed = new GZIPInputStream(new ByteArrayInputStream(compressedData));
                break;
            case COMPRESSION_DEFLATE:
                decompressed = new InflaterInputStream(new ByteArrayInputStream(compressedData));
                break;
            case COMPRESSION_NONE:
                decompressed = new ByteArrayInputStream(compressedData);
                break;
            default:
                return;
        }

        NbtCompound chunkNbt;
        try (DataInputStream dis = new DataInputStream(new BufferedInputStream(decompressed))) {
            chunkNbt = NbtIo.readCompound(dis);
        }

        if (chunkNbt == null)
            return;

        String status = chunkNbt.getString("Status").orElse(null);
        if (status == null)
            return;
        if (!status.equals("minecraft:full") && !status.equals("full"))
            return;

        NbtList sectionsList = chunkNbt.getList("sections").orElse(null);
        if (sectionsList == null || sectionsList.isEmpty())
            return;

        for (int si = 0; si < sectionsList.size(); si++) {
            NbtCompound sectionNbt = sectionsList.getCompound(si).orElse(null);
            if (sectionNbt == null)
                continue;

            importSectionNBT(chunkX, chunkZ, sectionNbt, mesher);
        }
    }

    private void importSectionNBT(int chunkX, int chunkZ, NbtCompound sectionNbt,
            GreedyMesher mesher) {
        int sectionY = sectionNbt.getByte("Y").orElse((byte) 0);

        NbtCompound blockStatesNbt = sectionNbt.getCompound("block_states").orElse(null);
        if (blockStatesNbt == null || blockStatesNbt.isEmpty())
            return;

        long sectionKey = LodSection.packKey(chunkX, sectionY, chunkZ);
        if (storage.getSection(sectionKey) != null)
            return;

        try {
            PalettedContainer<BlockState> blockStates = PalettedContainer.createPalettedContainerCodec(
                    BlockState.CODEC,
                    PaletteProvider.forBlockStates(Block.STATE_IDS),
                    net.minecraft.block.Blocks.AIR.getDefaultState()).parse(NbtOps.INSTANCE, blockStatesNbt).result()
                    .orElse(null);

            if (blockStates == null)
                return;

            PackedSectionData packed = new PackedSectionData(chunkX, sectionY, chunkZ);
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

            if (nonAir == 0)
                return;
            packed.nonAirCount = nonAir;

            packed.computeDistantShadows();

            byte[] skyLightBytes = sectionNbt.getByteArray("SkyLight").orElse(null);
            byte[] blockLightBytes = sectionNbt.getByteArray("BlockLight").orElse(null);

            if (skyLightBytes != null && skyLightBytes.length == 2048) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            int index = (y << 8) | (z << 4) | x;
                            int nibbleIdx = index >> 1;
                            int sky = (index & 1) == 0
                                    ? (skyLightBytes[nibbleIdx] & 0x0F)
                                    : ((skyLightBytes[nibbleIdx] >> 4) & 0x0F);
                            int blk = 0;
                            if (blockLightBytes != null && blockLightBytes.length == 2048) {
                                blk = (index & 1) == 0
                                        ? (blockLightBytes[nibbleIdx] & 0x0F)
                                        : ((blockLightBytes[nibbleIdx] >> 4) & 0x0F);
                            }
                            packed.packedLight[index] = (byte) (((sky & 0xF) << 4) | (blk & 0xF));
                        }
                    }
                }
            }

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

            GreedyMesher.MeshResult result = mesher.meshSection(blockGrid, null, lightGrid, null, null);
            if (result == null)
                return;

            LodSection section = new LodSection(chunkX, sectionY, chunkZ);
            section.setMeshData(
                    result.texturedVertices, result.texturedVertexCount,
                    result.compactVertices, result.compactVertexCount,
                    result.colorPalette, result.paletteSize,
                    result.boundsRadius);

            storage.putSectionWithKey(section);
            sectionsImported.incrementAndGet();

        } catch (Exception e) {
            errors.incrementAndGet();
        }
    }

    public void shutdown() {
        running = false;
        importing = false;

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

        regionQueue.clear();

        MetalLogger.info("[RegionFileImporter] Shutdown. Regions: {}, Chunks: {}, Sections: {}, Errors: {}",
                regionsProcessed.get(), chunksImported.get(), sectionsImported.get(), errors.get());
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isImporting() {
        return importing;
    }

    public int getRegionsProcessed() {
        return regionsProcessed.get();
    }

    public int getChunksImported() {
        return chunksImported.get();
    }

    public int getSectionsImported() {
        return sectionsImported.get();
    }

    public int getErrors() {
        return errors.get();
    }
}
