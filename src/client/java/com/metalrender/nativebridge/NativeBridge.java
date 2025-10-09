package com.metalrender.nativebridge;

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

    public static boolean isLibLoaded() {
        return libLoaded;
    }

    public static native boolean nIsAvailable();
    public static native long nInit(int width, int height, float scale);
    public static native void nResize(long handle, int width, int height, float scale);
    public static native void nBeginFrame(long handle, float[] proj, float[] view, float fogStart, float fogEnd);
    public static native void nDrawTerrain(long handle, int layerId);
    public static native void nDrawOverlay(long handle, int layerId);
    public static native void nOnWorldLoaded(long handle);
    public static native void nOnWorldUnloaded(long handle);
    public static native void nDestroy(long handle);
    public static native String nGetDeviceName();
    public static native boolean nSupportsIndirect();
    public static native boolean nSupportsMeshShaders();
}
