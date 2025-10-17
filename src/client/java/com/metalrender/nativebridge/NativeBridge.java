package com.metalrender.nativebridge;

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

   public static native void nBeginFrame(long var0, float[] var2, float[] var3, float var4, float var5);

   public static native void nDrawTerrain(long var0, int var2);

   public static native void nDrawOverlay(long var0, int var2);

   public static native void nOnWorldLoaded(long var0);

   public static native void nOnWorldUnloaded(long var0);

   public static native void nDestroy(long var0);

   public static native String nGetDeviceName();

   public static native boolean nSupportsIndirect();

   public static native boolean nSupportsMeshShaders();

   static {
      try {
         System.loadLibrary("metalrender");
         libLoaded = true;
      } catch (Throwable var1) {
         libLoaded = false;
      }

   }
}
