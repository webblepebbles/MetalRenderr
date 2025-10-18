package com.metalrender.render;

import com.metalrender.util.MetalLogger;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;

final class AsyncUploader {
  @FunctionalInterface
  interface UploadTask {
    void upload(ChunkBuildOutput output) throws Exception;
  }

  private final ExecutorService executor;
  private final AtomicBoolean running = new AtomicBoolean(true);
  private final AtomicInteger inFlight = new AtomicInteger();
  private final int maxInFlight;
  private final UploadTask task;
  private final Lock flushLock = new ReentrantLock();
  private final Condition flushCondition = flushLock.newCondition();

  AsyncUploader(int maxInFlight, UploadTask task) {
    this.maxInFlight = Math.max(1, maxInFlight);
    this.task = Objects.requireNonNull(task, "upload task");

    int workerCount = Math.min(8, Runtime.getRuntime().availableProcessors());
    ThreadFactory factory = runnable -> {
      Thread thread = new Thread(runnable, "MetalRender-AsyncUploader");
      thread.setDaemon(true);
      thread.setPriority(Thread.NORM_PRIORITY - 1);
      return thread;
    };
    this.executor = Executors.newFixedThreadPool(workerCount, factory);
    MetalLogger.info("[AsyncUploader] Initialized with %d worker threads",
                     workerCount);
  }

  boolean submit(ChunkBuildOutput output) {
    if (!this.running.get()) {
      this.executeSynchronously(output);
      return false;
    }
    if (this.inFlight.get() >= this.maxInFlight) {
      this.executeSynchronously(output);
      return false;
    }
    this.inFlight.incrementAndGet();
    this.executor.execute(() -> {
      try {
        this.task.upload(output);
      } catch (Throwable t) {
        MetalLogger.error("Async upload failed", t);
      } finally {
        int remaining = this.inFlight.decrementAndGet();
        if (remaining == 0) {
          flushLock.lock();
          try {
            flushCondition.signalAll();
          } finally {
            flushLock.unlock();
          }
        }
      }
    });
    return true;
  }

  void flush() {
    flushLock.lock();
    try {
      while (this.inFlight.get() > 0) {
        flushCondition.await();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } finally {
      flushLock.unlock();
    }
  }

  void shutdown() {
    if (this.running.compareAndSet(true, false)) {
      this.executor.shutdown();
      this.flush();
      this.executor.shutdownNow();
    }
  }

  private void executeSynchronously(ChunkBuildOutput output) {
    try {
      this.task.upload(output);
    } catch (Exception e) {
      MetalLogger.error("Synchronous upload failed", e);
    }
  }
}
