package com.pebbles_boon.metalrender.sodium.mixins;

import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.util.MetalLogger;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.SortedRenderLists;
import net.caffeinemc.mods.sodium.client.util.iterator.ByteIterator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(
    targets =
        "net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager",
    remap = false)
public abstract class ChunkRenderManagerMixin {

  private static long metalrender$lastLogMs;

  @Shadow public abstract SortedRenderLists getRenderLists();

  @Inject(method = "renderLayer", at = @At("HEAD"), cancellable = true,
          require = 0)
  private void
  metalrender$skipSodiumTerrainLayer(
      net.caffeinemc.mods.sodium.client.render.chunk
          .ChunkRenderMatrices matrices,
      net.caffeinemc.mods.sodium.client.render.chunk.terrain
          .TerrainRenderPass pass,
      double x, double y, double z,
      net.caffeinemc.mods.sodium.client.util.FogParameters fog,
      net.minecraft.client.gl.GpuSampler sampler, CallbackInfo ci) {
    if (MetalRenderClient.isMetalAvailable() &&
        MetalRenderClient.getConfig().enableMetalRendering) {

      if (MetalRenderClient.getConfig().mesherMode == 1) {
        com.pebbles_boon.metalrender.backend.MetalRenderer renderer =
            MetalRenderClient.getRenderer();
        int drawnCount = 0;
        if (renderer != null && renderer.getBackend() != null) {
          long frameCtx = renderer.getCurrentFrameContext();

          if (frameCtx != 0 &&
              renderer.getBackend().getDefaultPipelineHandle() != 0) {
            SortedRenderLists lists = this.getRenderLists();
            if (lists != null) {
              java.util.Iterator<ChunkRenderList> iter = lists.iterator(false);
              while (iter.hasNext()) {
                ChunkRenderList list = iter.next();
                ByteIterator iter2 = list.sectionsWithGeometryIterator(false);
                while (iter2.hasNext()) {
                  int id = iter2.nextByteAsInt();
                  RenderSection sec = list.getRegion().getSection(id);
                  com.pebbles_boon.metalrender.sodium.backend
                      .SodiumMetalInterface.MeshInfo info =
                      MetalRenderClient.getSodiumInterface().getSectionMesh(
                          sec.getChunkX(), sec.getChunkY(), sec.getChunkZ());
                  if (info != null && info.bufferId != 0) {
                    float cx = sec.getChunkX() * 16.0f - (float)x;
                    float cy = sec.getChunkY() * 16.0f - (float)y;
                    float cz = sec.getChunkZ() * 16.0f - (float)z;
                    com.pebbles_boon.metalrender.nativebridge.NativeBridge
                        .nSetChunkOffset(frameCtx, cx, cy, cz);
                    com.pebbles_boon.metalrender.nativebridge.NativeBridge
                        .nDrawBuffer(frameCtx, info.bufferId, info.vertexCount,
                                     0);
                    drawnCount++;
                  } else {
                    com.pebbles_boon.metalrender.util.MetalLogger.info(
                        "Missing mesh for chunk: " + sec.getChunkX() + "," +
                        sec.getChunkY() + "," + sec.getChunkZ());
                  }
                }
              }
              if (drawnCount > 0) {
                if (com.pebbles_boon.metalrender.MetalRenderClient
                            .getCoordinator() != null &&
                    com.pebbles_boon.metalrender.MetalRenderClient
                            .getCoordinator()
                            .getWorldRenderer() != null) {
                  com.pebbles_boon.metalrender.MetalRenderClient
                      .getCoordinator()
                      .getWorldRenderer()
                      .addDrawnChunkCount(drawnCount);
                }
              } else {
                com.pebbles_boon.metalrender.util.MetalLogger.info(
                    "No chunks drawn in renderLayer pass=" + pass.toString());
              }
            }

            long now = System.currentTimeMillis();
            if (now - metalrender$lastLogMs > 5000) {
              metalrender$lastLogMs = now;
              MetalLogger.info("Sodium terrain layer draw redirected to "
                               + "Metal. Meshes drawn directly!");
            }
            ci.cancel();
            return;
          }
        }
      }
    }
  }
}
