
package com.metalrender.backend;
import com.metalrender.nativebridge.MetalBackend;
import java.nio.ByteBuffer;

public final class MetalRenderer implements RenderBackend {
    private final long handle;
    private volatile boolean pipelinesReady;

    public MetalRenderer() {
        this.handle = MetalBackend.initNative(0L, false);
        if (this.handle == 0L) {
            throw new IllegalStateException("MetalBackend init failed: " + MetalBackend.getLastInitErrorNative());
        }
        this.pipelinesReady = MetalBackend.createTerrainPipelines(handle);
    }

    @Override
    public long createVertexBuffer(ByteBuffer data, int size, int stride) {
        if (data == null || size <= 0)
            return 0L;
        return MetalBackend.createVertexBuffer(handle, data, size);
    }

    @Override
    public long createIndexBuffer(ByteBuffer data, int size) {
        if (data == null || size <= 0)
            return 0L;
        return MetalBackend.createIndexBuffer(handle, data, size);
    }

    @Override
    public void destroyBuffer(long bufferHandle) {
        MetalBackend.destroyBuffer(handle, bufferHandle);
    }

    @Override
    public void beginFrame(float[] viewProj) {
        MetalBackend.setCamera(handle, viewProj);
    }

    @Override
    public void drawIndexed(long vbo, long ibo, int indexCount, int firstIndex, int baseVertex) {
        int pass = 0;
        if (pipelinesReady && MetalBackend.isPipelineReady(handle, pass)) {
            MetalBackend.drawIndexed(handle, vbo, ibo, indexCount, firstIndex, baseVertex, pass);
        }
    }

    @Override
    public void endFrame() {
        MetalBackend.render(handle, 0.0f);
    }

    public boolean isPassReady(int pass) {
        return pipelinesReady && MetalBackend.isPipelineReady(handle, pass);
    }
    public boolean isOpaqueReady() {
        return isPassReady(0);
    }
}
