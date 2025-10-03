package com.metalrender.nativebridge;

import java.util.concurrent.atomic.AtomicBoolean;

public final class NativeBridge {
    private static final AtomicBoolean LOADED = new AtomicBoolean(false);
    private static final AtomicBoolean AVAILABLE = new AtomicBoolean(false);

    private NativeBridge() {
    }

    public static boolean load() {
        if (LOADED.get())
            return AVAILABLE.get();
        boolean ok = false;
        Exception lastError = null;
        String cwd = System.getProperty("user.dir", ".");
        java.nio.file.Path[] candidates = new java.nio.file.Path[] {
                java.nio.file.Paths.get(cwd, "bin", "main", "libmetalrender.dylib"),
                java.nio.file.Paths.get(cwd, "build", "native", "libmetalrender.dylib"),
                java.nio.file.Paths.get(cwd, "src", "main", "resources", "libmetalrender.dylib")
        };
        for (java.nio.file.Path p : candidates) {
            try {
                if (java.nio.file.Files.isRegularFile(p)) {
                    System.load(p.toAbsolutePath().toString());
                    ok = nIsAvailable();
                    if (ok) {
                        com.metalrender.util.MetalLogger.info("Loaded native lib from " + p.toAbsolutePath());
                        break;
                    }
                }
            } catch (Exception e) {
                lastError = e;
            }
        }
        if (!ok) {
            try {
                System.loadLibrary("metalrender");
                ok = nIsAvailable();
                if (ok) {
                    com.metalrender.util.MetalLogger.info("Loaded native lib via System.loadLibrary");
                }
            } catch (Exception e) {
                lastError = e;
            }
        }
        if (!ok && lastError != null) {
            com.metalrender.util.MetalLogger.error("NativeBridge.load() failed: " + lastError);
        }
        AVAILABLE.set(ok);
        LOADED.set(true);
        return ok;
    }

    public static boolean isAvailable() {
        return LOADED.get() ? AVAILABLE.get() : load();
    }

    public static long init(int width, int height, float scale) {
        if (!isAvailable())
            return 0L;
        try {
            return nInit(width, height, scale);
        } catch (UnsatisfiedLinkError | IllegalArgumentException e) {
            com.metalrender.util.MetalLogger.error("NativeBridge.init failed: " + e);
            return 0L;
        }
    }

    public static void resize(long handle, int width, int height, float scale) {
        if (handle == 0L)
            return;
        try {
            nResize(handle, width, height, scale);
        } catch (UnsatisfiedLinkError | IllegalArgumentException e) {
            com.metalrender.util.MetalLogger.error("NativeBridge.resize failed: " + e);
        }
    }

    public static void beginFrame(long handle, float[] proj, float[] view, float fogStart, float fogEnd) {
        if (handle == 0L)
            return;
        try {
            nBeginFrame(handle, proj, view, fogStart, fogEnd);
        } catch (UnsatisfiedLinkError | IllegalArgumentException e) {
            com.metalrender.util.MetalLogger.error("NativeBridge.beginFrame failed: " + e);
        }
    }

    public static void drawTerrain(long handle, int layerId) {
        if (handle == 0L)
            return;
        try {
            nDrawTerrain(handle, layerId);
        } catch (UnsatisfiedLinkError | IllegalArgumentException e) {
            com.metalrender.util.MetalLogger.error("NativeBridge.drawTerrain failed: " + e);
        }
    }

    public static void drawOverlay(long handle, int layerId) {
        if (handle == 0L)
            return;
        try {
            nDrawOverlay(handle, layerId);
        } catch (UnsatisfiedLinkError | IllegalArgumentException e) {
            com.metalrender.util.MetalLogger.error("NativeBridge.drawOverlay failed: " + e);
        }
    }

    public static void onWorldLoaded(long handle) {
        if (handle == 0L)
            return;
        try {
            nOnWorldLoaded(handle);
        } catch (UnsatisfiedLinkError | IllegalArgumentException e) {
            com.metalrender.util.MetalLogger.error("NativeBridge.onWorldLoaded failed: " + e);
        }
    }

    public static void onWorldUnloaded(long handle) {
        if (handle == 0L)
            return;
        try {
            nOnWorldUnloaded(handle);
        } catch (UnsatisfiedLinkError | IllegalArgumentException e) {
            com.metalrender.util.MetalLogger.error("NativeBridge.onWorldUnloaded failed: " + e);
        }
    }

    public static void destroy(long handle) {
        if (handle == 0L)
            return;
        try {
            nDestroy(handle);
        } catch (UnsatisfiedLinkError | IllegalArgumentException e) {
            com.metalrender.util.MetalLogger.error("NativeBridge.destroy failed: " + e);
        }
    }

    public static String deviceName() {
        if (!isAvailable())
            return "unavailable";
        try {
            return nGetDeviceName();
        } catch (UnsatisfiedLinkError | IllegalArgumentException e) {
            return "unknown";
        }
    }

    public static boolean supportsIndirect() {
        if (!isAvailable())
            return false;
        try {
            return nSupportsIndirect();
        } catch (UnsatisfiedLinkError | IllegalArgumentException e) {
            return false;
        }
    }

    public static boolean supportsMeshShaders() {
        if (!isAvailable())
            return false;
        try {
            return nSupportsMeshShaders();
        } catch (UnsatisfiedLinkError | IllegalArgumentException e) {
            return false;
        }
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