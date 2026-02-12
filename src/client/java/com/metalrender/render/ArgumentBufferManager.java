package com.metalrender.render;

import com.metalrender.nativebridge.NativeBridge;
import com.metalrender.util.MetalLogger;
import java.util.HashMap;
import java.util.Map;

public class ArgumentBufferManager {

    private static final int BLOCK_ATLAS_INDEX = 0;
    private static final int BLOCK_ATLAS_SAMPLER_INDEX = 1;
    private static final int LOD_MIP_ATLAS_INDEX = 2;
    private static final int LOD_MIP_ATLAS_SAMPLER_INDEX = 3;
    private static final int ENTITY_TEXTURE_BASE_INDEX = 4;
    private static final int MAX_ENTITY_TEXTURES = 256;

    private final long deviceHandle;
    private long argBufferHandle = 0;
    private boolean initialized = false;

    private final Map<Long, Integer> textureIndexMap = new HashMap<>();
    private int nextEntityIndex = ENTITY_TEXTURE_BASE_INDEX;

    public ArgumentBufferManager(long deviceHandle) {
        this.deviceHandle = deviceHandle;
    }

    public boolean initialize() {
        if (initialized)
            return true;
        if (deviceHandle == 0L)
            return false;

        try {
            argBufferHandle = NativeBridge.nCreateArgumentBuffer(deviceHandle);
            if (argBufferHandle == 0L) {
                MetalLogger.warn("[ArgBuffer] Failed to create argument buffer");
                return false;
            }

            initialized = true;
            MetalLogger.info("[ArgBuffer] Created argument buffer (handle={})", argBufferHandle);
            return true;

        } catch (Throwable e) {
            MetalLogger.error("[ArgBuffer] Init failed: {}", e.getMessage());
            return false;
        }
    }

    public void setBlockAtlas(long textureHandle) {
        if (!initialized || deviceHandle == 0L)
            return;
        NativeBridge.nUpdateArgumentBuffer(deviceHandle, argBufferHandle,
                BLOCK_ATLAS_INDEX, textureHandle);
    }

    public void setLodMipAtlas(long textureHandle) {
        if (!initialized || deviceHandle == 0L)
            return;
        NativeBridge.nUpdateArgumentBuffer(deviceHandle, argBufferHandle,
                LOD_MIP_ATLAS_INDEX, textureHandle);
    }

    public int registerEntityTexture(long textureHandle) {
        if (!initialized)
            return -1;

        Integer existing = textureIndexMap.get(textureHandle);
        if (existing != null) {
            return existing;
        }

        if (nextEntityIndex >= ENTITY_TEXTURE_BASE_INDEX + MAX_ENTITY_TEXTURES) {
            MetalLogger.warn("[ArgBuffer] Entity texture limit reached ({})", MAX_ENTITY_TEXTURES);
            return -1;
        }

        int index = nextEntityIndex++;
        textureIndexMap.put(textureHandle, index);
        NativeBridge.nUpdateArgumentBuffer(deviceHandle, argBufferHandle,
                index, textureHandle);
        return index;
    }

    public void unregisterEntityTexture(long textureHandle) {
        textureIndexMap.remove(textureHandle);

    }

    public long getHandle() {
        return argBufferHandle;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public void destroy() {
        if (initialized && deviceHandle != 0L && argBufferHandle != 0L) {
            NativeBridge.nDestroyArgumentBuffer(deviceHandle, argBufferHandle);
        }
        argBufferHandle = 0;
        textureIndexMap.clear();
        nextEntityIndex = ENTITY_TEXTURE_BASE_INDEX;
        initialized = false;
    }
}
