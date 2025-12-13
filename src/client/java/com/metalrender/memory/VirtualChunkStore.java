package com.metalrender.memory;

import com.metalrender.util.MetalLogger;
import com.metalrender.lod.LODGenerator.LevelData;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import net.minecraft.util.math.BlockPos;


public final class VirtualChunkStore {
  private final Path baseDirectory;
  private final long budgetBytes;
  private final ReentrantLock lock = new ReentrantLock();
  private final Map<String, VirtualChunkHandle> lruPages =
      new LinkedHashMap<>(128, 0.75F, true);
  private long residentBytes;

  public VirtualChunkStore(Path baseDirectory, long budgetBytes) {
    this.baseDirectory = baseDirectory;
    this.budgetBytes = budgetBytes <= 0L ? 0L
                                         : Math.max(16L * 1024L * 1024L,
                                                    budgetBytes);
    try {
      Files.createDirectories(baseDirectory);
    } catch (IOException e) {
      MetalLogger.error("[VirtualChunkStore] Failed to create directory", e);
    }
  }

  public boolean isEnabled() {
    return budgetBytes > 0L;
  }

  public VirtualChunkHandle store(BlockPos origin, LevelData level) {
    if (!isEnabled() || level == null || level.vertexCount <= 0) {
      return null;
    }
    ByteBuffer data = level.buffer();
    if (!data.hasRemaining()) {
      return null;
    }
    String key = key(origin, level.level);
    Path file = baseDirectory.resolve(key + ".bin");
    lock.lock();
    long previousSize = fileSize(file);
    try (FileChannel channel = FileChannel.open(file,
             StandardOpenOption.CREATE, StandardOpenOption.WRITE,
             StandardOpenOption.TRUNCATE_EXISTING)) {
      ByteBuffer buffer = data.duplicate();
      buffer.position(0);
      while (buffer.hasRemaining()) {
        channel.write(buffer);
      }
      channel.force(false);
      long newSize = channel.size();
      VirtualChunkHandle handle = new VirtualChunkHandle(origin, level.level,
          level.vertexCount, level.boundsRadius, level.originalVertexCount,
          file);
      lruPages.put(key, handle);
      residentBytes = Math.max(0L, residentBytes - previousSize);
      residentBytes += newSize;
      evictIfNecessary();
      if (!lruPages.containsKey(key)) {
        MetalLogger.warn("Virtual chunk %s evicted immediately due to budget",
            key);
        return null;
      }
      return handle;
    } catch (IOException e) {
      MetalLogger.error("[VirtualChunkStore] Failed to write chunk page", e);
    } finally {
      lock.unlock();
    }
    return null;
  }

  public ByteBuffer load(VirtualChunkHandle handle) {
    if (handle == null || !isEnabled()) {
      return null;
    }
    lock.lock();
    ByteBuffer buffer = null;
    try {
      Path path = handle.path;
      if (!Files.exists(path)) {
        return null;
      }
      int size = (int) Math.min(Integer.MAX_VALUE, Files.size(path));
      buffer = VertexBufferPool.shared().acquire(size);
      buffer.clear();
      try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
        while (buffer.hasRemaining() && channel.read(buffer) > 0) {
    
        }
      }
      buffer.flip();
      String key = key(handle.origin, handle.lodLevel);
      VirtualChunkHandle existing = lruPages.get(key);
      if (existing == null) {
        lruPages.put(key, handle);
      }
      return buffer;
    } catch (IOException e) {
      MetalLogger.error("[VirtualChunkStore] Failed to load chunk page", e);
      if (buffer != null) {
        VertexBufferPool.shared().release(buffer);
      }
      return null;
    } finally {
      lock.unlock();
    }
  }

  public void release(ByteBuffer buffer) {
    VertexBufferPool.shared().release(buffer);
  }

  public void delete(BlockPos origin) {
    lock.lock();
    try {
      Iterator<Map.Entry<String, VirtualChunkHandle>> iterator =
          lruPages.entrySet().iterator();
      while (iterator.hasNext()) {
        VirtualChunkHandle handle = iterator.next().getValue();
        if (handle.origin.equals(origin)) {
          long size = fileSize(handle.path);
          residentBytes = Math.max(0L, residentBytes - size);
          iterator.remove();
          try {
            Files.deleteIfExists(handle.path);
          } catch (IOException ignored) {
          }
        }
      }
    } finally {
      lock.unlock();
    }
  }

  public void shutdown() {
    lock.lock();
    try {
      lruPages.clear();
    } finally {
      lock.unlock();
    }
  }

  private void evictIfNecessary() {
    Iterator<Map.Entry<String, VirtualChunkHandle>> iterator =
        lruPages.entrySet().iterator();
    while (residentBytes > budgetBytes && iterator.hasNext()) {
      VirtualChunkHandle handle = iterator.next().getValue();
      long size = fileSize(handle.path);
      try {
        Files.deleteIfExists(handle.path);
      } catch (IOException ignored) {
      }
      residentBytes = Math.max(0L, residentBytes - size);
      iterator.remove();
    }
  }

  private static String key(BlockPos origin, int level) {
    return origin.asLong() + "_" + level;
  }

  private static long fileSize(Path path) {
    try {
      return Files.exists(path) ? Files.size(path) : 0L;
    } catch (IOException e) {
      return 0L;
    }
  }
}
