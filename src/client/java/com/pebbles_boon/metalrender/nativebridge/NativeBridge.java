package com.pebbles_boon.metalrender.nativebridge;

public final class NativeBridge {
  private static volatile boolean libLoaded;

  static {
    try {
      System.loadLibrary("metalrender");
      libLoaded = true;
    } catch (Throwable t) {
      libLoaded = false;
    }
  }

  private NativeBridge() {}

  public static void loadLibrary() {
    if (!libLoaded) {
      System.loadLibrary("metalrender");
      libLoaded = true;
    }
  }

  public static boolean isLibLoaded() { return libLoaded; }

  public static native boolean nIsAvailable();

  public static native long nInit(int width, int height, float scale);

  public static native void nResize(long handle, int width, int height,
                                    float scale);

  public static native void nBeginFrame(long handle, float[] proj, float[] view,
                                        float fogStart, float fogEnd);

  public static native void nDrawTerrain(long handle, int layerId);

  public static native void nDrawOverlay(long handle, int layerId);

  public static native void nOnWorldLoaded(long handle);

  public static native void nOnWorldUnloaded(long handle);

  public static native void nDestroy(long handle);

  public static native String nGetDeviceName();

  public static native boolean nSupportsIndirect();

  public static native boolean nSupportsMeshShaders();

  public static native long nCreateBuffer(long deviceHandle, int sizeBytes,
                                          int storageMode);

  public static native void nUploadBufferData(long bufferHandle, byte[] data,
                                              int offset, int length);

  public static native void nDestroyBuffer(long bufferHandle);

  public static native void nSetPipelineState(long frameContext,
                                              long pipelineHandle);

  public static native void nSetChunkOffset(long frameContext, float x, float y,
                                            float z);

  public static native void nDrawIndexedBuffer(long frameContext,
                                               long vertexBuffer,
                                               long indexBuffer, int indexCount,
                                               int baseIndex);

  public static native void nDrawBuffer(long frameContext, long vertexBuffer,
                                        int vertexCount, int baseVertex);

  public static native long nGetCurrentFrameContext(long handle);

  public static native void nEndFrame(long handle);

  public static native void nSetProjectionMatrix(long handle, float[] matrix);

  public static native void nSetModelViewMatrix(long handle, float[] matrix);

  public static native void nSetCameraPosition(long handle, double x, double y,
                                               double z);

  public static native void nBindTexture(long handle, long textureHandle,
                                         int slot);

  public static native long nCreateTexture2D(long deviceHandle, int width,
                                             int height, byte[] pixelData);

  public static native long nGetDeviceHandle(long handle);

  public static native long nGetShaderLibraryHandle(long handle);

  public static native long nGetInhousePipelineHandle(long handle);

  public static native long nGetDefaultPipelineHandle(long handle);

  public static native int nGetGLTextureId(long handle);

  public static native int nGetIOSurfaceWidth(long handle);

  public static native int nGetIOSurfaceHeight(long handle);

  public static native void nWaitForRender(long handle);

  public static native boolean nBindIOSurfaceToTexture(long handle,
                                                       int glTexture);

  public static native boolean nReadbackPixels(long handle,
                                               java.nio.ByteBuffer dest);
}
