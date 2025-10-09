package com.metalrender.nativebridge;

import java.lang.reflect.Field;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import sun.misc.Unsafe;

public final class NativeMemory {
    private static final Unsafe UNSAFE;
    static {
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            UNSAFE = (Unsafe) f.get(null);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    private NativeMemory() {}

    public static long address(ByteBuffer directBuffer) {
        if (!directBuffer.isDirect())
            throw new IllegalArgumentException("Buffer must be direct");
        try {
            Field f = Buffer.class.getDeclaredField("address");
            f.setAccessible(true);
            return f.getLong(directBuffer);
        } catch (Throwable t) {
            throw new RuntimeException("Cannot access direct buffer address", t);
        }
    }

    public static ByteBuffer wrap(long addr, int size) {
        // Allocate a direct buffer and set address/limit via Unsafe. This is development-only util.
        ByteBuffer buf = ByteBuffer.allocateDirect(size);
        // We cannot set address safely without JDK internals; return a fresh buffer instead.
        // For JNI interop, prefer passing long addresses directly.
        return buf;
    }

    public static long malloc(long bytes) {
        return UNSAFE.allocateMemory(bytes);
    }

    public static void free(long addr) {
        UNSAFE.freeMemory(addr);
    }
}
