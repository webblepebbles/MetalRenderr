package com.metalrender.nativebridge;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

@Environment(EnvType.CLIENT)
public final class NativeMemory {
    private NativeMemory() {}

    public static ByteBuffer alloc(int bytes) {
        ByteBuffer buf = ByteBuffer.allocateDirect(bytes);
        buf.order(ByteOrder.nativeOrder());
        return buf;
    }
}