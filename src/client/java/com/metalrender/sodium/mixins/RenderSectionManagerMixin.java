package com.metalrender.sodium.mixins;

import com.metalrender.MetalRenderClient;
import com.metalrender.config.MetalRenderConfig;
import com.metalrender.lod.LodChunkIngestor;
import com.metalrender.render.MetalWorldRenderer;
import com.metalrender.sodium.format.MetalChunkVertex;
import com.metalrender.sodium.hooks.ChunkOutputBridge;
import java.util.Collection;
import com.metalrender.util.MetalLogger;
import net.caffeinemc.mods.sodium.client.gl.device.CommandList;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.BuilderTaskOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkMeshFormats;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager.class, remap = false)
public class RenderSectionManagerMixin {

  @Unique
  private boolean metalrender$pendingUploadFlush;
  @Unique
  private boolean metalrender$skipOutputDestroy;

  @Inject(method = "<init>", at = @At("TAIL"))
  private void metalrender$onConstructorTail(CallbackInfo ci) {

    if (MetalRenderClient.isEnabled()) {
      MetalLogger.info("[RenderSectionManagerMixin] Initialized with MetalChunkVertex");
    }
  }

  @Redirect(method = "<init>", at = @At(value = "FIELD", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/format/ChunkMeshFormats;COMPACT:Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/format/ChunkVertexType;"))
  private ChunkVertexType metalrender$redirectCompactFormat() {

    MetalLogger.info("[RenderSectionManagerMixin] Using Sodium's native COMPACT format (20 bytes)");
    return ChunkMeshFormats.COMPACT;
  }

  @Inject(method = "uploadChunks", at = @At("HEAD"))
  private void metalrender$onUploadChunksHead(CallbackInfo ci) {
    boolean solo = MetalRenderClient.issodiumless();
    this.metalrender$pendingUploadFlush = solo;
    this.metalrender$skipOutputDestroy = solo;
    if (solo) {
      if (MetalLogger.isDebugEnabled()) {
        MetalLogger.info("[RenderSectionManagerMixin] Solo-mode upload cycle detected; deferring Sodium uploads");
      }
    }
  }

  @Inject(method = "uploadChunks", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/BuilderTaskOutput;destroy()V", shift = At.Shift.BEFORE), remap = false)
  private void metalrender$flushChunkUploads(CallbackInfo ci) {
    if (!this.metalrender$pendingUploadFlush) {
      return;
    }

    this.metalrender$pendingUploadFlush = false;
    if (MetalLogger.isDebugEnabled()) {
      MetalLogger.info("[RenderSectionManagerMixin] Flushing Metal chunk uploads before Sodium destroy");
    }
    MetalWorldRenderer renderer = MetalRenderClient.getWorldRenderer();
    if (renderer != null) {
      renderer.flushChunkUploads();
    }
  }

  @Redirect(method = "processChunkBuildResults", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/region/RenderRegionManager;uploadResults"
      + "(Lnet/caffeinemc/mods/sodium/client/gl/device/CommandList;"
      + "Ljava/util/Collection;)V"), remap = false)
  private void metalrender$phaseChunkUploads(RenderRegionManager manager,
      CommandList commandList, Collection<BuilderTaskOutput> outputs) {

    int chunkCount = 0;
    if (outputs != null) {
      for (BuilderTaskOutput output : outputs) {
        if (output instanceof ChunkBuildOutput) {
          chunkCount++;
        }
      }
    }
    if (chunkCount > 0 && chunkCount % 50 == 1) {
      MetalLogger.info(
          "[RenderSectionManagerMixin] processChunkBuildResults called with %d chunk outputs (enabled=%s, sodiumless=%s)",
          chunkCount, MetalRenderClient.isEnabled(), MetalRenderClient.issodiumless());
    }

    if (MetalRenderClient.isEnabled() && outputs != null && !outputs.isEmpty()) {
      MetalWorldRenderer renderer = MetalRenderClient.getWorldRenderer();
      if (renderer != null) {
        for (BuilderTaskOutput output : outputs) {
          if (output instanceof ChunkBuildOutput build) {
            try {
              renderer.uploadBuildResult(build);
            } catch (Exception e) {
              MetalLogger.warn("[RenderSectionManagerMixin] Failed to upload chunk to Metal", e);
            }
          }
        }
      }
    }

    if (!MetalRenderClient.issodiumless()) {
      manager.uploadResults(commandList, outputs);
      return;
    }

    if (commandList != null) {
      try {
        commandList.close();
      } catch (Exception ignored) {
      }
    }
  }

  @Redirect(method = "uploadChunks", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/BuilderTaskOutput;destroy()V"), remap = false)
  private void metalrender$skipChunkDestroy(BuilderTaskOutput output) {
    if (!this.metalrender$skipOutputDestroy) {
      output.destroy();
      return;
    }

    if (!(output instanceof ChunkBuildOutput)) {
      try {
        output.destroy();
      } catch (Exception e) {
        MetalLogger.warn("[RenderSectionManagerMixin] Failed to destroy non-chunk output", e);
      }
    }
  }

  @Inject(method = "onChunkAdded", at = @At("HEAD"), require = 0)
  private void metalrender$captureChunkOnAdd(int x, int z, CallbackInfo ci) {
    if (MetalRenderConfig.extendedLodEnabled() && MetalRenderConfig.extendedLodIngestEnabled()) {
      LodChunkIngestor ingestor = LodChunkIngestor.getInstance();
      if (ingestor.isRunning()) {
        ingestor.captureChunkAt(x, z);
      }
    }
  }

  @Inject(method = "onChunkRemoved", at = @At("HEAD"), require = 0)
  private void metalrender$captureChunkOnRemove(int x, int z, CallbackInfo ci) {
    if (MetalRenderConfig.extendedLodEnabled() && MetalRenderConfig.extendedLodIngestEnabled()) {
      LodChunkIngestor ingestor = LodChunkIngestor.getInstance();
      if (ingestor.isRunning()) {
        ingestor.captureChunkAt(x, z);
      }
    }
  }
}
