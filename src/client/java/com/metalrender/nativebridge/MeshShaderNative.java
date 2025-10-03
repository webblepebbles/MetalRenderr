package com.metalrender.nativebridge;

import java.nio.ByteBuffer;

public final class MeshShaderNative {

    public static native long initMeshDevice(long nsWindow, boolean srgb);

    public static native boolean supportsMeshShaders(long deviceHandle);

    public static native void initMeshPipeline(long deviceHandle);

    public static native long createNativeChunkMesh(long deviceHandle,
            ByteBuffer vertexData, int vertexCount, int vertexStride,
            ByteBuffer indexData, int indexCount, int indexType);

    public static native long updateNativeChunkMesh(long existingHandle, long deviceHandle,
            ByteBuffer vertexData, int vertexCount, int vertexStride,
            ByteBuffer indexData, int indexCount, int indexType);

    public static native void destroyNativeChunkMesh(long meshHandle);

    public static native boolean startMeshFrame(long deviceHandle);

    public static native void drawNativeChunkMesh(long deviceHandle, long meshHandle, int layer);

    public static native void endMeshFrame(long deviceHandle);

    public static native void destroyMeshDevice(long deviceHandle);

    public static native void setShadersPath(String string);
}