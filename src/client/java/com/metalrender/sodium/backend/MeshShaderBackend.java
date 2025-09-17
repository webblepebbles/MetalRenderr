package com.metalrender.sodium.backend;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import com.metalrender.nativebridge.MeshShaderNative;
import com.metalrender.config.MetalRenderConfig;
import com.metalrender.util.MetalLogger;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Environment(EnvType.CLIENT)
public final class MeshShaderBackend {
    private final ExecutorService meshUploadExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "MeshShaderUploadThread");
        t.setDaemon(true);
        return t;
    });
    private final Queue<DrawRequest> drawQueue = new ConcurrentLinkedQueue<>();

    private static class DrawRequest {
        final BlockPos pos;
        final int layer;
        DrawRequest(BlockPos pos, int layer) {
            this.pos = pos;
            this.layer = layer;
        }
    }
    private long deviceHandle = 0L;
    private boolean initialized = false;
    private boolean meshEnabled = false;

    private final Map<BlockPos, Long> chunkHandles = new HashMap<>();
    private final Set<BlockPos> meshed = new HashSet<>();

    public synchronized boolean initIfNeeded() {
        if (initialized) return true;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getWindow() == null) {
            MetalLogger.error("MeshShaderBackend: missing MinecraftClient or Window");
            return false;
        }
        long glfwWindow = client.getWindow().getHandle();
        long nsWindow = org.lwjgl.glfw.GLFWNativeCocoa.glfwGetCocoaWindow(glfwWindow);
        boolean srgb = true;
        deviceHandle = MeshShaderNative.initMeshDevice(nsWindow, srgb);
        if (deviceHandle == 0L) {
            MetalLogger.error("MeshShaderBackend: initMeshDevice returned 0");
            return false;
        }
        try {
            meshEnabled = MetalRenderConfig.get().getAdvancedMetalFeatures().isMeshShadersEnabled()
                    && MeshShaderNative.supportsMeshShaders(deviceHandle);
        } catch (Throwable t) {
            MetalLogger.error("MeshShaderBackend: error probing mesh support");
            meshEnabled = false;
        }
        if (meshEnabled) {
            try {
                MeshShaderNative.initMeshPipeline(deviceHandle);
            } catch (Throwable t) {
                MetalLogger.error("MeshShaderBackend: initMeshPipeline failed");
                meshEnabled = false;
            }
        }
        initialized = true;
        MetalLogger.info("MeshShaderBackend initialized. meshEnabled=" + meshEnabled);
        return true;
    }

    public synchronized void destroy() {
        if (!initialized) return;
        for (Long h : chunkHandles.values()) {
            try { MeshShaderNative.destroyNativeChunkMesh(h); } catch (Throwable t) { MetalLogger.error("Error destroying chunk mesh"); }
        }
        chunkHandles.clear();
        meshed.clear();
        if (deviceHandle != 0L) {
            try { MeshShaderNative.destroyMeshDevice(deviceHandle); } catch (Throwable t) { MetalLogger.error("Error destroying mesh device"); }
            deviceHandle = 0L;
        }
        initialized = false;
    }


    public Future<Long> uploadChunkMeshAsync(BlockPos pos, ByteBuffer vertexData, int vertexCount, int vertexStride,
                                             ByteBuffer indexData, int indexCount, int indexType) {
        return meshUploadExecutor.submit(() -> uploadChunkMeshInternal(pos, vertexData, vertexCount, vertexStride, indexData, indexCount, indexType));
    }


    private synchronized long uploadChunkMeshInternal(BlockPos pos, ByteBuffer vertexData, int vertexCount, int vertexStride,
                                                     ByteBuffer indexData, int indexCount, int indexType) {
        if (!initialized) return 0L;
        if (!meshEnabled) return 0L;
        if (vertexData == null || !vertexData.isDirect()) {
            return 0L;
        }
        try {
            long h = MeshShaderNative.createNativeChunkMesh(deviceHandle, vertexData, vertexCount, vertexStride,
                    indexData, indexCount, indexType);
            if (h != 0L) {
                chunkHandles.put(pos, h);
                meshed.add(pos);
                return h;
            } else {
                return 0L;
            }
        } catch (Throwable t) {
            return 0L;
        }
    }
    public long uploadChunkMesh(BlockPos pos, ByteBuffer vertexData, int vertexCount, int vertexStride,
                                ByteBuffer indexData, int indexCount, int indexType) {
        try {
            return uploadChunkMeshAsync(pos, vertexData, vertexCount, vertexStride, indexData, indexCount, indexType).get();
        } catch (Exception e) {
            return 0L;
        }
    }

    public synchronized void removeChunkMesh(BlockPos pos) {
        Long h = chunkHandles.remove(pos);
        if (h != null && h != 0L) {
            try { MeshShaderNative.destroyNativeChunkMesh(h); } catch (Throwable t) { MetalLogger.error("Error destroying native mesh"); }
        }
        meshed.remove(pos);
    }

    public void beginFrame() {
        if (!initialized || !meshEnabled) return;
        try {
            boolean ok = MeshShaderNative.startMeshFrame(deviceHandle);
            if (!ok) {
            }
        } catch (Throwable t) {
            MetalLogger.error("MeshShaderBackend: error");
        }
    }
    public void queueDrawChunkLayer(BlockPos pos, int layer) {
        drawQueue.add(new DrawRequest(pos, layer));
    }

   

    public void processDrawQueue() {
        if (!initialized || !meshEnabled) return;
        DrawRequest req;
        while ((req = drawQueue.poll()) != null) {
            Long h = chunkHandles.get(req.pos);
            if (h != null && h != 0L) {
                try {
                    MeshShaderNative.drawNativeChunkMesh(deviceHandle, h, req.layer);
                } catch (Throwable t) {
                 
                }
            }
        }
    }
    public void endFrame() {
        if (!initialized || !meshEnabled) return;
        try {
            MeshShaderNative.endMeshFrame(deviceHandle);
        } catch (Throwable t) {
            MetalLogger.error("MeshShaderBackend: error");
        }
    }
    public boolean isMeshEnabled() {
        return meshEnabled;
    }
}
