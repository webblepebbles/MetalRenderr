package com.metalrender.backend;

import com.metalrender.nativebridge.MetalBackend;
import java.nio.ByteBuffer;

public final class MetalRenderer implements RenderBackend {
  private final long handle = MetalBackend.initNative(0L, false);
  private volatile boolean pipelinesReady;

  public MetalRenderer() {
    if (this.handle == 0L) {
      throw new IllegalStateException("MetalBackend init failed: " +
                                      MetalBackend.getLastInitErrorNative());
    } else {
      this.pipelinesReady = MetalBackend.createTerrainPipelines(this.handle);
    }
  }

  public long createVertexBuffer(ByteBuffer data, int size, int stride) {
    return data != null && size > 0
        ? MetalBackend.createVertexBuffer(this.handle, data, size)
        : 0L;
  }

  public long createIndexBuffer(ByteBuffer data, int size) {
    return data != null && size > 0
        ? MetalBackend.createIndexBuffer(this.handle, data, size)
        : 0L;
  }

  public void destroyBuffer(long bufferHandle) {
    MetalBackend.destroyBuffer(this.handle, bufferHandle);
  }

  public void beginFrame(float[] viewProj) {
    MetalBackend.setCamera(this.handle, viewProj);
  }

  public void drawIndexed(long vbo, long ibo, int indexCount, int firstIndex,
                          int baseVertex) {
    int pass = 0;
    if (this.pipelinesReady &&
        MetalBackend.isPipelineReady(this.handle, pass)) {
      MetalBackend.drawIndexed(this.handle, vbo, ibo, indexCount, firstIndex,
                               baseVertex, pass);
    }
  }

  public void endFrame() { MetalBackend.render(this.handle, 0.0F); }

  public boolean isPassReady(int pass) {
    return this.pipelinesReady &&
        MetalBackend.isPipelineReady(this.handle, pass);
  }

  public boolean isOpaqueReady() { return this.isPassReady(0); }
}
