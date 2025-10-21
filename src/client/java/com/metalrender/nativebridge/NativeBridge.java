package com.metalrender.nativebridge;

import java.nio.Buffer;

public final class NativeBridge {
  private static volatile boolean libLoaded;

  private NativeBridge() {}

  public static boolean isLibLoaded() { return libLoaded; }

  public static native boolean nIsAvailable();

  public static native long nInit(int var0, int var1, float var2);

  public static native void nResize(long var0, int var2, int var3, float var4);

  public static native void nBeginFrame(long var0, float[] var2, float[] var3,
                                        float var4, float var5);

  public static native void nDrawTerrain(long var0, int var2);

  public static native void nDrawOverlay(long var0, int var2);

  public static native void nOnWorldLoaded(long var0);

  public static native void nOnWorldUnloaded(long var0);

  public static native void nDestroy(long var0);

  public static native String nGetDeviceName(long var0);

  public static native boolean nSupportsIndirect();

  public static native boolean nSupportsMeshShaders();

  public static native boolean nSupportsHiZ(long var0);

  public static native long nEnsureHiZ(long var0, int var2, int var3);

  public static native void nDestroyHiZ(long var0, long var2);

  public static native void nOcclusionBegin(long var0, long var2, float[] var4);

  public static native void
  nOcclusionEvaluate(long var0, long var2, Buffer var4, int var5, Buffer var6);

  public static native java.nio.ByteBuffer nMapPersistentBuffer(long var0);

  public static native int nPersistentCapacity(long var0);

  public static native int nPersistentAlign(long var0);

  public static native void nPersistentAdvance(long var0, int var2);

  public static native void nClearIndirectCommands(long var0);

  public static native void nQueueIndirectDraw(long var0, int var2, long var3,
                                               long var5, int var7, int var8,
                                               int var9, int var10, int var11,
                                               float var12);

  public static native int nExecuteIndirect(long var0, int var2);

  public static native void nPrewarmPipelines(long var0);

  public static native int[] nGetPipelineCacheStats(long var0);

  public static native void nResetPipelineCache(long var0);

  public static native void nSetTemporalJitter(long var0, float var2,
                                               float var3, float var4);

  public static native boolean nSupportsMetalFX();

  public static native void nSetMetalFXEnabled(long var0, boolean var2);

  public static native void nConfigureMetalFX(long var0, int var2, int var3,
                                              float var4);

  static {
    try {
      System.loadLibrary("metalrender");
      libLoaded = true;
      String libPath = System.mapLibraryName("metalrender");
      System.err.println("[MetalRender] Loaded native library: " + libPath);
      System.err.println("[MetalRender] java.library.path=" +
                         System.getProperty("java.library.path"));
    } catch (Throwable var1) {
      libLoaded = false;
      System.err.println("[MetalRender] Failed to load native library: " +
                         var1);
      var1.printStackTrace();
      System.err.println("[MetalRender] java.library.path=" +
                         System.getProperty("java.library.path"));
    }
  }
}
