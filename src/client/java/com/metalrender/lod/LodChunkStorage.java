package com.metalrender.lod;

import com.metalrender.util.MetalLogger;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public final class LodChunkStorage {

    private static final int MAGIC = 0x4D4C4F44;
    private static final int FORMAT_VERSION = 1;
    private static final int HEADER_SIZE = 4096;
    private static final int INDEX_ENTRY_SIZE = 20;

    private static final int DEFAULT_CACHE_SIZE = 2048;
    private final Long2ObjectLinkedOpenHashMap<LodSection> cache;
    private final int maxCacheSize;
    private long totalCacheBytes;
    private static final long MAX_CACHE_BYTES = 512L * 1024 * 1024;

    private final Long2ObjectOpenHashMap<long[]> diskIndex;

    private final Path storagePath;
    private RandomAccessFile dataFile;
    private FileChannel dataChannel;
    private long dataFileSize;
    private long nextWriteOffset;

    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private volatile boolean closed = false;

    private long sectionsWritten = 0;
    private long sectionsRead = 0;
    private long cacheHits = 0;
    private long cacheMisses = 0;

    public LodChunkStorage(Path storagePath, int cacheSize) {
        this.storagePath = storagePath;
        this.maxCacheSize = cacheSize;
        this.cache = new Long2ObjectLinkedOpenHashMap<>(cacheSize);
        this.diskIndex = new Long2ObjectOpenHashMap<>(4096);
        this.totalCacheBytes = 0;
    }

    public LodChunkStorage(Path storagePath) {
        this(storagePath, DEFAULT_CACHE_SIZE);
    }

    public boolean initialize() {
        lock.writeLock().lock();
        try {

            Path parent = storagePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            boolean isNew = !Files.exists(storagePath);
            dataFile = new RandomAccessFile(storagePath.toFile(), "rw");
            dataChannel = dataFile.getChannel();

            if (isNew) {

                writeHeader();
                dataFileSize = HEADER_SIZE;
                nextWriteOffset = HEADER_SIZE;
                MetalLogger.info("[LodChunkStorage] Created new storage: {}", storagePath);
            } else {

                dataFileSize = dataFile.length();
                if (!readHeader()) {
                    MetalLogger.error("[LodChunkStorage] Invalid storage file: {}", storagePath);
                    return false;
                }
                loadIndex();
                MetalLogger.info("[LodChunkStorage] Loaded storage: {} ({} sections, {} MB)",
                        storagePath, diskIndex.size(), dataFileSize / (1024 * 1024));
            }

            return true;
        } catch (IOException e) {
            MetalLogger.error("[LodChunkStorage] Failed to initialize: {}", e.getMessage());
            return false;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public LodSection getSection(int sectionX, int sectionY, int sectionZ) {
        long key = LodSection.packKey(sectionX, sectionY, sectionZ);
        return getSection(key);
    }

    public LodSection getSection(long key) {
        if (closed)
            return null;

        lock.readLock().lock();
        try {
            LodSection cached = cache.getAndMoveToLast(key);
            if (cached != null) {
                cacheHits++;
                return cached;
            }
        } finally {
            lock.readLock().unlock();
        }

        lock.writeLock().lock();
        try {
            cacheMisses++;
            long[] indexEntry = diskIndex.get(key);
            if (indexEntry == null)
                return null;

            long offset = indexEntry[0];
            int size = (int) indexEntry[1];

            ByteBuffer buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
            dataChannel.read(buf, offset);
            buf.flip();

            LodSection section = LodSection.deserialize(key, buf);
            if (section != null) {
                putInCache(key, section);
                sectionsRead++;
            }
            return section;
        } catch (IOException e) {
            MetalLogger.error("[LodChunkStorage] Read failed for key {}: {}", key, e.getMessage());
            return null;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void putSection(LodSection section) {
        if (closed || section == null)
            return;

        long key = section.key();
        ByteBuffer serialized = section.serialize();
        int dataSize = serialized.remaining();

        lock.writeLock().lock();
        try {

            long writeOffset = nextWriteOffset;
            dataChannel.write(serialized, writeOffset);
            nextWriteOffset += dataSize;
            dataFileSize = Math.max(dataFileSize, nextWriteOffset);

            diskIndex.put(key, new long[] { writeOffset, dataSize });

            putInCache(key, section);

            sectionsWritten++;

            if (sectionsWritten % 100 == 0) {
                dataChannel.force(false);
            }
        } catch (IOException e) {
            MetalLogger.error("[LodChunkStorage] Write failed for section ({},{},{}): {}",
                    section.sectionX, section.sectionY, section.sectionZ, e.getMessage());
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean hasSection(long key) {
        lock.readLock().lock();
        try {
            return cache.containsKey(key) || diskIndex.containsKey(key);
        } finally {
            lock.readLock().unlock();
        }
    }

    public int getSectionCount() {
        lock.readLock().lock();
        try {
            return diskIndex.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    private void putInCache(long key, LodSection section) {

        while (cache.size() >= maxCacheSize || totalCacheBytes >= MAX_CACHE_BYTES) {
            if (cache.isEmpty())
                break;
            long oldestKey = cache.firstLongKey();
            LodSection evicted = cache.removeFirst();
            if (evicted != null) {
                totalCacheBytes -= evicted.estimatedMemoryBytes();
            }
        }

        LodSection old = cache.putAndMoveToLast(key, section);
        if (old != null) {
            totalCacheBytes -= old.estimatedMemoryBytes();
        }
        totalCacheBytes += section.estimatedMemoryBytes();
    }

    private void writeHeader() throws IOException {
        ByteBuffer header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(MAGIC);
        header.putInt(FORMAT_VERSION);
        header.putInt(0);

        header.position(0);
        dataChannel.write(header, 0);
    }

    private boolean readHeader() throws IOException {
        if (dataFileSize < HEADER_SIZE)
            return false;

        ByteBuffer header = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
        dataChannel.read(header, 0);
        header.flip();

        int magic = header.getInt();
        int version = header.getInt();
        int sectionCount = header.getInt();

        if (magic != MAGIC) {
            MetalLogger.error("[LodChunkStorage] Bad magic: 0x{}", Integer.toHexString(magic));
            return false;
        }
        if (version != FORMAT_VERSION) {
            MetalLogger.error("[LodChunkStorage] Unsupported version: {}", version);
            return false;
        }

        return true;
    }

    private void loadIndex() throws IOException {
        long pos = HEADER_SIZE;
        int loaded = 0;

        while (pos < dataFileSize) {

            ByteBuffer peekBuf = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN);
            int bytesRead = dataChannel.read(peekBuf, pos);
            if (bytesRead < 28)
                break;
            peekBuf.flip();

            int magic = peekBuf.getInt(0);
            if (magic != 0x4C4F4453) {

                pos++;
                continue;
            }

            peekBuf.position(6);
            int palSize = peekBuf.getShort() & 0xFFFF;
            int texVertCount = peekBuf.getInt();
            int compVertCount = peekBuf.getInt();

            int sectionSize = 28
                    + palSize * 4
                    + texVertCount * LodVertexFormat.STRIDE_LOD_TEXTURED
                    + compVertCount * LodVertexFormat.STRIDE_LOD_COMPACT;

            if (pos + sectionSize > dataFileSize)
                break;

            break;
        }

        nextWriteOffset = HEADER_SIZE;
        diskIndex.clear();

        pos = HEADER_SIZE;
        while (pos + 8 < dataFileSize) {

            ByteBuffer keyBuf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
            int bytesRead = dataChannel.read(keyBuf, pos);
            if (bytesRead < 8)
                break;
            keyBuf.flip();
            long key = keyBuf.getLong();

            long dataStart = pos + 8;

            ByteBuffer peekBuf = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN);
            bytesRead = dataChannel.read(peekBuf, dataStart);
            if (bytesRead < 28)
                break;
            peekBuf.flip();

            int magic = peekBuf.getInt(0);
            if (magic != 0x4C4F4453) {
                pos++;
                continue;
            }

            peekBuf.position(6);
            int palSize = peekBuf.getShort() & 0xFFFF;
            int texVertCount = peekBuf.getInt();
            int compVertCount = peekBuf.getInt();

            int sectionSize = 28
                    + palSize * 4
                    + texVertCount * LodVertexFormat.STRIDE_LOD_TEXTURED
                    + compVertCount * LodVertexFormat.STRIDE_LOD_COMPACT;

            if (dataStart + sectionSize > dataFileSize)
                break;

            diskIndex.put(key, new long[] { dataStart, sectionSize });

            pos = dataStart + sectionSize;
            loaded++;
        }

        nextWriteOffset = pos;
        MetalLogger.info("[LodChunkStorage] Index loaded: {} sections", loaded);
    }

    public void putSectionWithKey(LodSection section) {
        if (closed || section == null)
            return;

        long key = section.key();
        ByteBuffer serialized = section.serialize();
        int dataSize = serialized.remaining();

        lock.writeLock().lock();
        try {
            long writeOffset = nextWriteOffset;

            ByteBuffer keyBuf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
            keyBuf.putLong(key);
            keyBuf.flip();
            dataChannel.write(keyBuf, writeOffset);

            dataChannel.write(serialized, writeOffset + 8);
            nextWriteOffset += 8 + dataSize;
            dataFileSize = Math.max(dataFileSize, nextWriteOffset);

            diskIndex.put(key, new long[] { writeOffset + 8, dataSize });

            putInCache(key, section);
            sectionsWritten++;

            if (sectionsWritten % 100 == 0) {
                dataChannel.force(false);
            }
        } catch (IOException e) {
            MetalLogger.error("[LodChunkStorage] Write failed: {}", e.getMessage());
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void flush() {
        lock.writeLock().lock();
        try {
            if (dataChannel != null && dataChannel.isOpen()) {
                dataChannel.force(true);
            }
        } catch (IOException e) {
            MetalLogger.error("[LodChunkStorage] Flush failed: {}", e.getMessage());
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void close() {
        lock.writeLock().lock();
        try {
            closed = true;

            if (dataChannel != null && dataChannel.isOpen()) {
                ByteBuffer countBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
                countBuf.putInt(diskIndex.size());
                countBuf.flip();
                dataChannel.write(countBuf, 8);
                dataChannel.force(true);
                dataChannel.close();
            }
            if (dataFile != null) {
                dataFile.close();
            }

            cache.clear();
            totalCacheBytes = 0;

            MetalLogger.info("[LodChunkStorage] Closed. Written={}, Read={}, CacheHit={}%, Sections={}",
                    sectionsWritten, sectionsRead,
                    (cacheHits + cacheMisses) > 0 ? (cacheHits * 100 / (cacheHits + cacheMisses)) : 0,
                    diskIndex.size());
        } catch (IOException e) {
            MetalLogger.error("[LodChunkStorage] Close failed: {}", e.getMessage());
        } finally {
            lock.writeLock().unlock();
        }
    }

    public String getDebugInfo() {
        lock.readLock().lock();
        try {
            return String.format("LOD Storage: %d sections, %d cached (%d MB), %d MB on disk, hit=%d%%",
                    diskIndex.size(),
                    cache.size(),
                    totalCacheBytes / (1024 * 1024),
                    dataFileSize / (1024 * 1024),
                    (cacheHits + cacheMisses) > 0 ? (cacheHits * 100 / (cacheHits + cacheMisses)) : 0);
        } finally {
            lock.readLock().unlock();
        }
    }
}
