package com.metalrender.nativebridge;

import java.nio.Buffer;

public final class NativeBridge {
  private static volatile boolean libLoaded;

  private NativeBridge() {
  }

  public static boolean isLibLoaded() {
    return libLoaded;
  }

  public static native boolean nIsAvailable();

  public static native long nInit(int var0, int var1, float var2);

  public static native void nResize(long var0, int var2, int var3, float var4);

  private static native void nBeginFrame_jni(long var0, float[] var2, float[] var3,
      float var4, float var5);

  public static void nBeginFrame(long handle, float[] viewProj, float[] cameraPos,
      float fogStart, float fogEnd) {
    if (PanamaMetalBridge.isAvailable()) {
      PanamaMetalBridge.beginFrame(handle, viewProj, cameraPos, fogStart, fogEnd);
    } else {
      nBeginFrame_jni(handle, viewProj, cameraPos, fogStart, fogEnd);
    }
  }

  private static native void nDrawTerrain_jni(long var0, int var2);

  public static void nDrawTerrain(long handle, int pass) {
    if (PanamaMetalBridge.isAvailable()) {
      PanamaMetalBridge.drawTerrain(handle, pass);
    } else {
      nDrawTerrain_jni(handle, pass);
    }
  }

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

  public static native void nOcclusionEvaluate(long var0, long var2, Buffer var4, int var5, Buffer var6);

  public static native java.nio.ByteBuffer nMapPersistentBuffer(long var0);

  public static native int nPersistentCapacity(long var0);

  public static native int nPersistentAlign(long var0);

  public static native void nPersistentAdvance(long var0, int var2);

  private static native void nClearIndirectCommands_jni(long var0);

  public static void nClearIndirectCommands(long handle) {
    if (PanamaMetalBridge.isAvailable()) {
      PanamaMetalBridge.clearIndirectCommands(handle);
    } else {
      nClearIndirectCommands_jni(handle);
    }
  }

  public static native void nQueueIndirectDraw(long var0, int var2, long var3,
      long var5, int var7, int var8,
      int var9, int var10, int var11,
      float var12);

  private static native void nBatchDrawCommands_jni(long handle,
      java.nio.ByteBuffer buffer, int commandCount);

  public static void nBatchDrawCommands(long handle, java.nio.ByteBuffer buffer, int commandCount) {
    if (PanamaMetalBridge.isAvailable()) {
      PanamaMetalBridge.batchDrawCommands(handle, buffer, commandCount);
    } else {
      nBatchDrawCommands_jni(handle, buffer, commandCount);
    }
  }

  private static native int nExecuteIndirect_jni(long var0, int var2);

  public static int nExecuteIndirect(long handle, int pass) {
    if (PanamaMetalBridge.isAvailable()) {
      return PanamaMetalBridge.executeIndirect(handle, pass);
    } else {
      return nExecuteIndirect_jni(handle, pass);
    }
  }

  public static native void nPrewarmPipelines(long var0);

  public static native int[] nGetPipelineCacheStats(long var0);

  public static native void nResetPipelineCache(long var0);

  private static native void nSetTemporalJitter_jni(long var0, float var2,
      float var3, float var4);

  public static void nSetTemporalJitter(long handle, float jitterX, float jitterY, float blendFactor) {
    if (PanamaMetalBridge.isAvailable()) {
      PanamaMetalBridge.setTemporalJitter(handle, jitterX, jitterY, blendFactor);
    } else {
      nSetTemporalJitter_jni(handle, jitterX, jitterY, blendFactor);
    }
  }

  private static native void nSetLightParams_jni(long handle, float dayBrightness, float ambientLight, float skyAngle);

  public static void nSetLightParams(long handle, float dayBrightness, float ambientLight, float skyAngle) {
    if (PanamaMetalBridge.isAvailable()) {
      PanamaMetalBridge.setLightParams(handle, dayBrightness, ambientLight, skyAngle);
    } else {
      nSetLightParams_jni(handle, dayBrightness, ambientLight, skyAngle);
    }
  }

  public static native boolean nSupportsMetalFX();

  public static native void nSetMetalFXEnabled(long var0, boolean var2);

  public static native void nConfigureMetalFX(long var0, int var2, int var3,
      float var4);

  public static native int nGetIOSurfaceWidth(long var0);

  public static native int nGetIOSurfaceHeight(long var0);

  public static native boolean nBindIOSurfaceToTexture(long var0, int var2);

  public static native boolean nReadbackPixels(long var0, java.nio.ByteBuffer var2);

  public static native void nWaitForRender(long var0);

  public static native long nGetDeviceMemory(long var0);

  public static native long nGetMemoryUsage(long var0);

  public static native void nSetShadersPath(long var0, String var2);

  public static native boolean nUploadAtlas(long var0, java.nio.ByteBuffer var2, int var3, int var4);

  public static native boolean nAttachSurface(long var0, long var2);

  public static native void nDetachSurface(long var0);

  public static native boolean nPresentFrame(long handle);

  public static native long nCreateFence(long var0);

  public static native int nWaitFence(long var0, long var2, long var4);

  public static native boolean nPollFence(long var0, long var2);

  public static native void nDestroyFence(long var0, long var2);

  public static native long nUploadEntityTexture(long handle, java.nio.ByteBuffer pixels, int width, int height);

  public static native void nDestroyEntityTexture(long handle, long textureHandle);

  private static native void nBeginEntityPass_jni(long handle, float[] viewProj, float[] cameraPos);

  public static void nBeginEntityPass(long handle, float[] viewProj, float[] cameraPos) {
    if (PanamaMetalBridge.isAvailable()) {
      PanamaMetalBridge.beginEntityPass(handle, viewProj, cameraPos);
    } else {
      nBeginEntityPass_jni(handle, viewProj, cameraPos);
    }
  }

  private static native void nDrawEntity_jni(long handle, java.nio.ByteBuffer vertices, int vertexCount,
      long textureHandle);

  public static void nDrawEntity(long handle, java.nio.ByteBuffer vertices, int vertexCount, long textureHandle) {
    if (PanamaMetalBridge.isAvailable()) {
      PanamaMetalBridge.drawEntity(handle, vertices, vertexCount, textureHandle);
    } else {
      nDrawEntity_jni(handle, vertices, vertexCount, textureHandle);
    }
  }

  public static native void nDrawItem(long handle, java.nio.ByteBuffer vertices, int vertexCount, long textureHandle,
      boolean useDepthTest);

  private static native void nEndEntityPass_jni(long handle);

  public static void nEndEntityPass(long handle) {
    if (PanamaMetalBridge.isAvailable()) {
      PanamaMetalBridge.endEntityPass(handle);
    } else {
      nEndEntityPass_jni(handle);
    }
  }

  private static native void nBatchDrawEntities_jni(long handle, java.nio.ByteBuffer vertexBuf,
      int totalVertexBytes, java.nio.ByteBuffer cmdBuf, int cmdCount);

  public static void nBatchDrawEntities(long handle, java.nio.ByteBuffer vertexBuf,
      int totalVertexBytes, java.nio.ByteBuffer cmdBuf, int cmdCount) {
    if (PanamaMetalBridge.isAvailable()) {
      PanamaMetalBridge.batchDrawEntities(handle, vertexBuf, totalVertexBytes, cmdBuf, cmdCount);
    } else {
      nBatchDrawEntities_jni(handle, vertexBuf, totalVertexBytes, cmdBuf, cmdCount);
    }
  }

  public static native void nBeginItemPass(long handle, float[] viewProj, float[] cameraPos);

  public static native void nEndItemPass(long handle);

  public static native void nBeginGuiPass(long handle, float[] viewProj, float[] cameraPos);

  public static native void nEndGuiPass(long handle);

  public static native void nClearDepthForHand(long handle);

  public static native void nCompositeGui(long handle);

  public static native boolean nInitMultiICB(long handle, int maxChunksPerLOD);

  public static native void nDestroyMultiICB(long handle);

  public static native void nDispatchGpuLodSelect(long handle, float[] cameraPos,
      float[] frustumPlanes, int totalChunks);

  public static native int nExecuteMultiICB(long handle);

  public static native int[] nGetLodDrawCounts(long handle);

  public static native void nSetLodThresholds(long handle, float[] thresholds);

  public static native void nSetLodFunctionConstants(long handle, int lodLevel,
      boolean enableBlockLight, boolean enableTextureSample, boolean enableFog);

  public static native void nSetTripleBuffering(long handle, boolean enabled);

  public static native int nGetCurrentFrameIndex(long handle);

  public static native boolean nInitMetalFXSpatial(long handle, int renderWidth,
      int renderHeight, int outputWidth, int outputHeight);

  public static native void nApplyMetalFXSpatial(long handle);

  public static native void nDestroyMetalFXSpatial(long handle);

  public static native long nCreateArgumentBuffer(long handle);

  public static native void nUpdateArgumentBuffer(long handle, long argBuffer,
      int index, long textureHandle);

  public static native void nDestroyArgumentBuffer(long handle, long argBuffer);

  public static native boolean nInitComputeMesher(long handle, int maxVertsPerSection);

  public static native int nDispatchComputeMesh(long handle, java.nio.ByteBuffer stagingBuffer,
      int outputOffset, int sectionX, int sectionY, int sectionZ, int maxVerts);

  public static native void nDestroyComputeMesher(long handle);

  public static native void nSetThreadQoS(int qosClass);

  static {
    try {
      System.loadLibrary("metalrender_debug_v2");
      libLoaded = true;
      String libPath = System.mapLibraryName("metalrender_debug_v2");
      System.err.println("[MetalRender] Loaded native library: " + libPath);
      System.err.println("[MetalRender] java.library.path=" +
          System.getProperty("java.library.path"));
      try {
        PanamaMetalBridge.init();
        System.err.println("[MetalRender] Panama FFI: " +
            (PanamaMetalBridge.isAvailable() ? "ACTIVE" : "unavailable, using JNI"));
      } catch (Throwable p) {
        System.err.println("[MetalRender] Panama init skipped: " + p);
      }
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
