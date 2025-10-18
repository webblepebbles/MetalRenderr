package com.metalrender.backend;

import java.nio.ByteBuffer;

public interface RenderBackend {
  long createVertexBuffer(ByteBuffer var1, int var2, int var3);

  long createIndexBuffer(ByteBuffer var1, int var2);

  void destroyBuffer(long var1);

  void beginFrame(float[] var1);

  void drawIndexed(long var1, long var3, int var5, int var6, int var7);

  void endFrame();
}
