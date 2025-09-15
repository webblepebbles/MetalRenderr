package com.metalrender.nativebridge;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;

import java.nio.ByteBuffer;

@Environment(EnvType.CLIENT)
public final class MetalBackend {
    static {
        try {
            System.loadLibrary("metalrender");
        } catch (Throwable t) {
        }
    }

    public static long init(long windowHandle, boolean someFlag) {
        if (!MetalHardwareChecker.isCompatible()) {
            MinecraftClient.getInstance().execute(() -> MetalHardwareChecker.showIncompatibleScreen());
            return 0;
        }
        return initNative(windowHandle, someFlag);
    }

    private static native long initNative(long windowHandle, boolean someFlag);
    public static native void uploadStaticMesh(long handle, ByteBuffer vertexData, int vertexCount, int stride);
    public static native void resize(long handle, int width, int height);
    public static native void setCamera(long handle, float[] viewProj4x4);
    public static native void render(long handle, float timeSeconds);
    public static native void destroy(long handle);
    public static native boolean supportsMeshShaders();
}