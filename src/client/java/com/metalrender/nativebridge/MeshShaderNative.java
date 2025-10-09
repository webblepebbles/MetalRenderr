package com.metalrender.nativebridge;

public final class MeshShaderNative {
    private MeshShaderNative() {}

    public static native void dispatchTerrain(long handle, int visibleRegions, long indirectBufferAddr);
}
