package com.metalrender.sodium.backend;

import java.nio.ByteBuffer;
import net.minecraft.util.math.BlockPos;

public class MeshShaderBackend {
    private boolean enabled = false;

    public boolean initIfNeeded() {
        enabled = true;
        return true;
    }
    public boolean isMeshEnabled() {
        return enabled;
    }
    public void destroy() {
        enabled = false;
    }

    public void uploadChunkMeshAsync(
        BlockPos chunkPos, ByteBuffer buf, int vertexCount, int stride, Object arg0, int arg1, int arg2) {
        // Stub: will forward to native later
    }

    public void removeChunkMesh(BlockPos chunkPos) {}
}
