package com.metalrender.nativebridge;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.nio.file.Path;

public final class PanamaMetalBridge {
    private static volatile boolean available = false;
    private static SymbolLookup lib;
    private static Linker linker;

    private static MethodHandle mhBeginFrame;
    private static MethodHandle mhDrawTerrain;
    private static MethodHandle mhClearIndirectCommands;
    private static MethodHandle mhBatchDrawCommands;
    private static MethodHandle mhExecuteIndirect;
    private static MethodHandle mhSetTemporalJitter;
    private static MethodHandle mhSetLightParams;
    private static MethodHandle mhBeginEntityPass;
    private static MethodHandle mhDrawEntity;
    private static MethodHandle mhEndEntityPass;
    private static MethodHandle mhBatchDrawEntities;

    private PanamaMetalBridge() {
    }

    public static boolean isAvailable() {
        return false;
    }

    @SuppressWarnings("preview")
    public static void init() {
        try {
            linker = Linker.nativeLinker();

            String libName = System.mapLibraryName("metalrender_debug_v2");
            String libPath = findLibrary(libName);
            if (libPath == null) {
                System.err.println("[PanamaMetalBridge] Could not find " + libName);
                return;
            }

            lib = SymbolLookup.libraryLookup(Path.of(libPath), Arena.global());

            mhBeginFrame = downcall("mr_beginFrame",
                    FunctionDescriptor.ofVoid(
                            ValueLayout.JAVA_LONG,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_FLOAT,
                            ValueLayout.JAVA_FLOAT));

            mhDrawTerrain = downcall("mr_drawTerrain",
                    FunctionDescriptor.ofVoid(
                            ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_INT));

            mhClearIndirectCommands = downcall("mr_clearIndirectCommands",
                    FunctionDescriptor.ofVoid(ValueLayout.JAVA_LONG));

            mhBatchDrawCommands = downcall("mr_batchDrawCommands",
                    FunctionDescriptor.ofVoid(
                            ValueLayout.JAVA_LONG,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_INT));

            mhExecuteIndirect = downcall("mr_executeIndirect",
                    FunctionDescriptor.of(
                            ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_INT));

            mhSetTemporalJitter = downcall("mr_setTemporalJitter",
                    FunctionDescriptor.ofVoid(
                            ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_FLOAT,
                            ValueLayout.JAVA_FLOAT,
                            ValueLayout.JAVA_FLOAT));

            mhSetLightParams = downcall("mr_setLightParams",
                    FunctionDescriptor.ofVoid(
                            ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_FLOAT,
                            ValueLayout.JAVA_FLOAT,
                            ValueLayout.JAVA_FLOAT));

            mhBeginEntityPass = downcall("mr_beginEntityPass",
                    FunctionDescriptor.ofVoid(
                            ValueLayout.JAVA_LONG,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS));

            mhDrawEntity = downcall("mr_drawEntity",
                    FunctionDescriptor.ofVoid(
                            ValueLayout.JAVA_LONG,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_LONG));

            mhEndEntityPass = downcall("mr_endEntityPass",
                    FunctionDescriptor.ofVoid(ValueLayout.JAVA_LONG));

            mhBatchDrawEntities = downcall("mr_batchDrawEntities",
                    FunctionDescriptor.ofVoid(
                            ValueLayout.JAVA_LONG,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_INT));

            available = true;
            System.err.println("[PanamaMetalBridge] Initialized — 11 downcall handles ready");
        } catch (Throwable t) {
            available = false;
            System.err.println("[PanamaMetalBridge] Init failed: " + t);
            t.printStackTrace();
        }
    }

    private static MethodHandle downcall(String name, FunctionDescriptor desc) {
        MemorySegment symbol = lib.find(name)
                .orElseThrow(() -> new UnsatisfiedLinkError("Symbol not found: " + name));
        return linker.downcallHandle(symbol, desc);
    }

    private static String findLibrary(String libName) {
        String[] searchPaths = {
                System.getProperty("java.library.path", ""),
                "src/main/resources",
                "build/resources/main",
                "."
        };
        for (String base : searchPaths) {
            for (String dir : base.split(System.getProperty("path.separator"))) {
                if (dir.isEmpty())
                    continue;
                java.io.File f = new java.io.File(dir, libName);
                if (f.exists())
                    return f.getAbsolutePath();
            }
        }
        java.net.URL url = PanamaMetalBridge.class.getClassLoader().getResource(libName);
        if (url != null && "file".equals(url.getProtocol())) {
            return url.getPath();
        }
        return null;
    }

    @SuppressWarnings("preview")
    public static void beginFrame(long handle, float[] viewProj, float[] cameraPos,
            float fogStart, float fogEnd) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment vpSeg = arena.allocate(viewProj.length * Float.BYTES, Float.BYTES);
            MemorySegment.copy(viewProj, 0, vpSeg, ValueLayout.JAVA_FLOAT, 0, viewProj.length);

            MemorySegment cpSeg = arena.allocate(cameraPos.length * Float.BYTES, Float.BYTES);
            MemorySegment.copy(cameraPos, 0, cpSeg, ValueLayout.JAVA_FLOAT, 0, cameraPos.length);

            mhBeginFrame.invokeExact(handle, vpSeg, cpSeg, fogStart, fogEnd);
        } catch (Throwable t) {
            throw new RuntimeException("Panama beginFrame failed", t);
        }
    }

    public static void drawTerrain(long handle, int pass) {
        try {
            mhDrawTerrain.invokeExact(handle, pass);
        } catch (Throwable t) {
            throw new RuntimeException("Panama drawTerrain failed", t);
        }
    }

    public static void clearIndirectCommands(long handle) {
        try {
            mhClearIndirectCommands.invokeExact(handle);
        } catch (Throwable t) {
            throw new RuntimeException("Panama clearIndirectCommands failed", t);
        }
    }

    @SuppressWarnings("preview")
    public static void batchDrawCommands(long handle, ByteBuffer buffer, int commandCount) {
        try {
            MemorySegment seg = MemorySegment.ofBuffer(buffer);
            mhBatchDrawCommands.invokeExact(handle, seg, commandCount);
        } catch (Throwable t) {
            throw new RuntimeException("Panama batchDrawCommands failed", t);
        }
    }

    public static int executeIndirect(long handle, int pass) {
        try {
            return (int) mhExecuteIndirect.invokeExact(handle, pass);
        } catch (Throwable t) {
            throw new RuntimeException("Panama executeIndirect failed", t);
        }
    }

    public static void setTemporalJitter(long handle, float jitterX, float jitterY,
            float blendFactor) {
        try {
            mhSetTemporalJitter.invokeExact(handle, jitterX, jitterY, blendFactor);
        } catch (Throwable t) {
            throw new RuntimeException("Panama setTemporalJitter failed", t);
        }
    }

    public static void setLightParams(long handle, float dayBrightness, float ambientLight,
            float skyAngle) {
        try {
            mhSetLightParams.invokeExact(handle, dayBrightness, ambientLight, skyAngle);
        } catch (Throwable t) {
            throw new RuntimeException("Panama setLightParams failed", t);
        }
    }

    @SuppressWarnings("preview")
    public static void beginEntityPass(long handle, float[] viewProj, float[] cameraPos) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment vpSeg = arena.allocate(viewProj.length * Float.BYTES, Float.BYTES);
            MemorySegment.copy(viewProj, 0, vpSeg, ValueLayout.JAVA_FLOAT, 0, viewProj.length);

            MemorySegment cpSeg = arena.allocate(cameraPos.length * Float.BYTES, Float.BYTES);
            MemorySegment.copy(cameraPos, 0, cpSeg, ValueLayout.JAVA_FLOAT, 0, cameraPos.length);

            mhBeginEntityPass.invokeExact(handle, vpSeg, cpSeg);
        } catch (Throwable t) {
            throw new RuntimeException("Panama beginEntityPass failed", t);
        }
    }

    @SuppressWarnings("preview")
    public static void drawEntity(long handle, ByteBuffer vertices, int vertexCount,
            long textureHandle) {
        try {
            MemorySegment seg = MemorySegment.ofBuffer(vertices);
            mhDrawEntity.invokeExact(handle, seg, vertexCount, textureHandle);
        } catch (Throwable t) {
            throw new RuntimeException("Panama drawEntity failed", t);
        }
    }

    public static void endEntityPass(long handle) {
        try {
            mhEndEntityPass.invokeExact(handle);
        } catch (Throwable t) {
            throw new RuntimeException("Panama endEntityPass failed", t);
        }
    }

    @SuppressWarnings("preview")
    public static void batchDrawEntities(long handle, ByteBuffer vertexBuf,
            int totalVertexBytes, ByteBuffer cmdBuf, int cmdCount) {
        try {
            MemorySegment vtxSeg = MemorySegment.ofBuffer(vertexBuf);
            MemorySegment cmdSeg = MemorySegment.ofBuffer(cmdBuf);
            mhBatchDrawEntities.invokeExact(handle, vtxSeg, totalVertexBytes, cmdSeg, cmdCount);
        } catch (Throwable t) {
            throw new RuntimeException("Panama batchDrawEntities failed", t);
        }
    }
}
