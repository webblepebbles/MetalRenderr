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

  public static native void nOcclusionEvaluate(long var0, long var2, Buffer var4, int var5, Buffer var6);

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

  // IOSurface methods
  public static native int nGetIOSurfaceWidth(long var0);

  public static native int nGetIOSurfaceHeight(long var0);

  public static native boolean nBindIOSurfaceToTexture(long var0, int var2);

  // CPU readback - copy Metal render target to ByteBuffer
  public static native boolean nReadbackPixels(long var0, java.nio.ByteBuffer var2);

  // Wait for Metal command buffer to complete - ensures IOSurface has data
  public static native void nWaitForRender(long var0);

  // Memory methods
  public static native long nGetDeviceMemory(long var0);

  public static native long nGetMemoryUsage(long var0);

  // Shader path
  public static native void nSetShadersPath(long var0, String var2);

  // Atlas upload
  public static native boolean nUploadAtlas(long var0, java.nio.ByteBuffer var2, int var3, int var4);

  // Surface attachment
  public static native boolean nAttachSurface(long var0, long var2);

  public static native void nDetachSurface(long var0);

  // Direct Metal presentation (Full Metal mode)
  public static native boolean nPresentFrame(long handle);

  // Fence synchronization
  public static native long nCreateFence(long var0);

  public static native int nWaitFence(long var0, long var2, long var4);

  public static native boolean nPollFence(long var0, long var2);

  public static native void nDestroyFence(long var0, long var2);

  // =========================================================================
  // Entity Rendering (TODO: implement in native code)
  // =========================================================================

  /**
   * Upload an entity texture to Metal.
   * 
   * @param handle Device handle
   * @param pixels RGBA pixel data
   * @param width  Texture width
   * @param height Texture height
   * @return Texture handle, or 0 on failure
   */
  public static native long nUploadEntityTexture(long handle, java.nio.ByteBuffer pixels, int width, int height);

  /**
   * Destroy an entity texture.
   */
  public static native void nDestroyEntityTexture(long handle, long textureHandle);

  /**
   * Begin entity rendering pass.
   * Sets up the entity pipeline state.
   */
  public static native void nBeginEntityPass(long handle, float[] viewProj, float[] cameraPos);

  /**
   * Draw entity vertices with a texture.
   * 
   * @param handle        Device handle
   * @param vertices      Vertex data (32 bytes per vertex: pos, uv, color,
   *                      normal, padding)
   * @param vertexCount   Number of vertices
   * @param textureHandle Entity texture handle
   */
  public static native void nDrawEntity(long handle, java.nio.ByteBuffer vertices, int vertexCount, long textureHandle);

  /**
   * Draw item vertices with depth testing control.
   * Used for GUI items where 3D blocks need depth testing but flat items don't.
   * 
   * @param handle        Device handle
   * @param vertices      Vertex data (32 bytes per vertex: pos, uv, color,
   *                      normal, padding)
   * @param vertexCount   Number of vertices
   * @param textureHandle Entity texture handle
   * @param useDepthTest  Whether to enable depth testing (true for 3D block
   *                      items)
   */
  public static native void nDrawItem(long handle, java.nio.ByteBuffer vertices, int vertexCount, long textureHandle,
      boolean useDepthTest);

  /**
   * End entity rendering pass.
   */
  public static native void nEndEntityPass(long handle);

  /**
   * Begin item rendering pass.
   * Similar to entity pass but optimized for GUI items.
   * Uses depth testing for 3D blocks to render correctly as cubes.
   */
  public static native void nBeginItemPass(long handle, float[] viewProj, float[] cameraPos);

  /**
   * End item rendering pass.
   */
  public static native void nEndItemPass(long handle);

  /**
   * Begin GUI rendering pass with no depth testing.
   * GUI always renders in front of everything.
   */
  public static native void nBeginGuiPass(long handle, float[] viewProj, float[] cameraPos);

  /**
   * End GUI rendering pass.
   */
  public static native void nEndGuiPass(long handle);

  /**
   * Clear depth buffer for hand/item rendering.
   * This ensures hand always renders in front of terrain.
   * Call this before nBeginEntityPass when rendering the hand.
   */
  public static native void nClearDepthForHand(long handle);

  /**
   * Composite GUI texture over terrain in ioSurfaceTexture.
   * Call this after nEndGuiPass and before nReadbackPixels/blit.
   */
  public static native void nCompositeGui(long handle);

  static {
    try {
      System.loadLibrary("metalrender_debug_v2");
      libLoaded = true;
      String libPath = System.mapLibraryName("metalrender_debug_v2");
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
