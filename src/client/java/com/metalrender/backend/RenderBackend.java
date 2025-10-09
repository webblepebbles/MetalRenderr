package com.metalrender.backend;

import java.nio.ByteBuffer;

public interface RenderBackend {
    // Resource creation
    long createVertexBuffer(ByteBuffer data, int size, int stride);
    long createIndexBuffer(ByteBuffer data, int size);
    void destroyBuffer(long handle);

    // Frame control
    void beginFrame(float[] viewProj);
    void drawIndexed(long vbo, long ibo, int indexCount, int firstIndex, int baseVertex);
    void endFrame();
}
