package com.metalrender.sodium.hooks;

import com.metalrender.render.MetalWorldRenderer;
import com.metalrender.util.MetalLogger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.BuilderTaskOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;

public final class ChunkOutputBridge {
  private static final Queue<ChunkBuildOutput> QUEUE = new ConcurrentLinkedQueue<>();

  private ChunkOutputBridge() {
  }

  public static void enqueueAll(Collection<BuilderTaskOutput> outputs) {
    if (outputs == null || outputs.isEmpty()) {
      return;
    }

    int chunkCount = 0;
    for (BuilderTaskOutput output : outputs) {
      if (output instanceof ChunkBuildOutput build) {
        QUEUE.add(build);
        chunkCount++;
      } else if (output != null) {
        try {
          output.destroy();
        } catch (Exception e) {
          MetalLogger.warn("[ChunkOutputBridge] Failed to destroy non-mesh output", e);
        }
      }
    }

    if (chunkCount > 0) {
      MetalLogger.info("[ChunkOutputBridge] Enqueued %d chunks (queue size now %d)", chunkCount, QUEUE.size());
    }
  }

  public static int drain(MetalWorldRenderer renderer) {
    if (renderer == null) {
      return 0;
    }

    List<ChunkBuildOutput> drained = new ArrayList<>();
    ChunkBuildOutput output;
    while ((output = QUEUE.poll()) != null) {
      drained.add(output);
    }

    if (drained.isEmpty()) {
      return 0;
    }

    MetalLogger.info("[ChunkOutputBridge] Draining %d chunk outputs to Metal", drained.size());

    try {
      renderer.uploadBuildResults(drained);
    } catch (Exception e) {
      MetalLogger.error("[ChunkOutputBridge] Batched upload failed", e);
      for (ChunkBuildOutput failed : drained) {
        if (failed == null) {
          continue;
        }
        try {
          failed.destroy();
        } catch (Exception destroyError) {
          MetalLogger.warn("[ChunkOutputBridge] Failed to destroy chunk output after error", destroyError);
        }
      }
    }
    return drained.size();
  }

  public static void clear() {
    QUEUE.clear();
  }
}
