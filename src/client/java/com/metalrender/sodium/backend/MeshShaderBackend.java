package com.metalrender.sodium.backend;

import java.nio.ByteBuffer;
import net.minecraft.util.math.BlockPos;

public class MeshShaderBackend {
   private boolean enabled = false;

   public boolean initIfNeeded() {
      this.enabled = true;
      return true;
   }

   public boolean isMeshEnabled() {
      return this.enabled;
   }

   public void destroy() {
      this.enabled = false;
   }

   public void uploadChunkMeshAsync(BlockPos chunkPos, ByteBuffer buf, int vertexCount, int stride, Object arg0, int arg1, int arg2) {
   }

   public void removeChunkMesh(BlockPos chunkPos) {
   }
}
