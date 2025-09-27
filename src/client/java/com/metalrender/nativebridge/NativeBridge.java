package com.metalrender.nativebridge;

import java.util.concurrent.atomic.AtomicBoolean;

public final class NativeBridge {
    private static final AtomicBoolean LOADED = new AtomicBoolean(false);
    private static final AtomicBoolean AVAILABLE = new AtomicBoolean(false);

    private NativeBridge() {}

    public static boolean load() {
            if (LOADED.get()) return AVAILABLE.get();
            boolean ok = false;
            try {
                System.loadLibrary("metalrender");
                ok = nIsAvailable();
            } catch (Throwable t) {
                com.metalrender.util.MetalLogger.error("NativeBridge.load() failed: " + t);
            }
            AVAILABLE.set(ok);
            LOADED.set(true);
            return ok;
    }
    public static boolean isAvailable() {
        return LOADED.get() ? AVAILABLE.get() : load();
    }
    public static long init(int width, int height, float scale) {
        if (!isAvailable()) return 0L;
        try {
            return nInit(width, height, scale);
        } catch (Throwable t) {
            return 0L;
        }
    }
    public static void resize(long handle, int width, int height, float scale) {
        if (handle == 0L) return;
        try { nResize(handle, width, height, scale); } catch (Throwable ignored) {}
    }
    public static void beginFrame(long handle, float[] proj, float[] view, float fogStart, float fogEnd) {
        if (handle == 0L) return;
        try { nBeginFrame(handle, proj, view, fogStart, fogEnd); } catch (Throwable ignored) {}
    }
    public static void drawTerrain(long handle, int layerId) {
        if (handle == 0L) return;
        try { nDrawTerrain(handle, layerId); } catch (Throwable ignored) {}
    }
    public static void drawOverlay(long handle, int layerId) {
        if (handle == 0L) return;
        try { nDrawOverlay(handle, layerId); } catch (Throwable ignored) {}
    }
    public static void onWorldLoaded(long handle) {
        if (handle == 0L) return;
        try { nOnWorldLoaded(handle); } catch (Throwable ignored) {}
    }
    public static void onWorldUnloaded(long handle) {
        if (handle == 0L) return;
        try { nOnWorldUnloaded(handle); } catch (Throwable ignored) {}
    }
    public static void destroy(long handle) {
        if (handle == 0L) return;
        try { nDestroy(handle); } catch (Throwable ignored) {}
    }
    public static String deviceName() {
        if (!isAvailable()) return "unavailable";
        try { return nGetDeviceName(); } catch (Throwable t) { return "unknown"; }
    }
    public static boolean supportsIndirect() {
        if (!isAvailable()) return false;
        try { return nSupportsIndirect(); } catch (Throwable t) { return false; }
    }
    public static boolean supportsMeshShaders() {
        if (!isAvailable()) return false;
        try { return nSupportsMeshShaders(); } catch (Throwable t) { return false; }
    }
    private static native boolean nIsAvailable();
    private static native long nInit(int width, int height, float scale);
    private static native void nResize(long handle, int width, int height, float scale);
    private static native void nBeginFrame(long handle, float[] proj, float[] view, float fogStart, float fogEnd);
    private static native void nDrawTerrain(long handle, int layerId);
    private static native void nDrawOverlay(long handle, int layerId);
    private static native void nOnWorldLoaded(long handle);
    private static native void nOnWorldUnloaded(long handle);
    private static native void nDestroy(long handle);
    private static native String nGetDeviceName();
    private static native boolean nSupportsIndirect();
    private static native boolean nSupportsMeshShaders();
}