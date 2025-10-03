package com.metalrender.nativebridge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class NativeMemory {
    private NativeMemory() {
    }

    public static ByteBuffer alloc(int bytes) {
        ByteBuffer buf = ByteBuffer.allocateDirect(bytes);
        buf.order(ByteOrder.nativeOrder());
        return buf;
    }
}