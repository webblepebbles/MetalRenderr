package com.metalrender.nativebridge;

import java.nio.Buffer;

public final class MetalBackend {
   private MetalBackend() {
   }

   public static native long initNative(long var0, boolean var2);

   public static native void uploadStaticMesh(long var0, Buffer var2, int var3, int var4);

   public static native void resize(long var0, int var2, int var3);

   public static native void setCamera(long var0, float[] var2);

   public static native void render(long var0, float var2);

   public static native void destroy(long var0);

   public static native boolean supportsMeshShaders();

   public static native String getLastInitErrorNative();

   public static native long createVertexBuffer(long var0, Buffer var2, int var3);

   public static native long createIndexBuffer(long var0, Buffer var2, int var3);

   public static native void destroyBuffer(long var0, long var2);

   public static native boolean createTerrainPipelines(long var0);

   public static native boolean isPipelineReady(long var0, int var2);

   public static native void drawIndexed(long var0, long var2, long var4, int var6, int var7, int var8, int var9);
}
