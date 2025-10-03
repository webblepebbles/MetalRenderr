package com.metalrender.sodium.backend;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import com.metalrender.nativebridge.MeshShaderNative;
import com.metalrender.nativebridge.NativeBridge;
import com.metalrender.config.MetalRenderConfig;
import com.metalrender.util.MetalLogger;
import com.metalrender.util.FrustumCuller;
import com.metalrender.util.OcclusionCuller;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.util.Window;
import org.joml.Matrix4f;
import net.minecraft.util.math.BlockPos;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Set;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Environment(EnvType.CLIENT)
public final class MeshShaderBackend {
    private final ExecutorService meshUploadExecutor = Executors.newFixedThreadPool(
        Math.max(2, Runtime.getRuntime().availableProcessors() / 2), r -> {
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
    private final Map<BlockPos, Long> chunkHandles = new java.util.concurrent.ConcurrentHashMap<>();
    private final Set<BlockPos> meshed = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
    private final java.util.concurrent.ConcurrentLinkedQueue<Long> meshPool = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final java.util.concurrent.atomic.AtomicInteger poolSize = new java.util.concurrent.atomic.AtomicInteger(0);
    private final int MAX_POOL_SIZE = Math.max(8, Runtime.getRuntime().availableProcessors() * 4);
    private volatile boolean nativeUpdateSupported = false;
    private volatile boolean nativeUpdateProbed = false;
    private final FrustumCuller frustumCuller = new FrustumCuller();
    private final OcclusionCuller occlusionCuller = new OcclusionCuller();


    public synchronized boolean initIfNeeded() {
        if (initialized) {
            MetalLogger.info("MeshShaderBackend already initialized");
            return true;
        }
       
        MetalLogger.info("MeshShaderBackend initialising");
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getWindow() == null) {
            MetalLogger.error("MeshShaderBackend missing MinecraftClient or Window");
            return false;
        }
        long ctx = org.lwjgl.glfw.GLFW.glfwGetCurrentContext();
        MetalLogger.info("GLFW context=" + ctx);
        if (ctx == 0L) {
            MetalLogger.error("GLFW context not ready");
            return false;
        }
        if (!NativeBridge.load()) {
            MetalLogger.error("NativeBridge.load() failed");
            return false;
        }
        long glfwWindow = client.getWindow().getHandle();
        MetalLogger.info("glfwWindow handle=" + glfwWindow);
        long nsWindow = org.lwjgl.glfw.GLFWNativeCocoa.glfwGetCocoaWindow(glfwWindow);
        MetalLogger.info("nsWindow=" + nsWindow);
        boolean srgb = true;
        deviceHandle = MeshShaderNative.initMeshDevice(nsWindow, srgb);
        MetalLogger.info("initMeshDevice returned deviceHandle=" + deviceHandle);
        if (deviceHandle == 0L) {
            MetalLogger.error("initMeshDevice returned 0");
            return false;
        }
    
        try {
            java.nio.file.Path tempDir = java.nio.file.Files.createTempDirectory("metalrender-shaders");
            tempDir.toFile().deleteOnExit();
            java.nio.file.Path out = tempDir.resolve("shaders.metallib");
            try (java.io.InputStream in = MeshShaderBackend.class.getClassLoader().getResourceAsStream("shaders.metallib")) {
                if (in != null) {
                    java.nio.file.Files.copy(in, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    MeshShaderNative.setShadersPath(out.toAbsolutePath().toString());
                    MetalLogger.info("Provided shaders.metallib to native at: " + out);
                } else {
                    MetalLogger.warn("shaders.metallib not found in resources; native will try default library");
                }
            }
        } catch (Throwable t) {
            MetalLogger.warn("Unable to provision shaders.metallib: " + t);
        }
        try {
            boolean configEnabled = MetalRenderConfig.get().getAdvancedMetalFeatures().isMeshShadersEnabled();
            MetalLogger.info("Config mesh shaders enabled: " + configEnabled);
            if (configEnabled) {
                boolean nativeSupport = MeshShaderNative.supportsMeshShaders(deviceHandle);
                MetalLogger.info("Native mesh shaders supported: " + nativeSupport);
                meshEnabled = nativeSupport;
            } else {
                meshEnabled = false;
            }
            MetalLogger.info("meshEnabled=" + meshEnabled);
        } catch (Throwable t) {
            MetalLogger.error("error probing mesh support: " + t);
            meshEnabled = false;
        }
        if (meshEnabled) {
            try {
                MeshShaderNative.initMeshPipeline(deviceHandle);
                MetalLogger.info("Mesh pipeline initialized");
                if (!nativeUpdateProbed) {
                    nativeUpdateProbed = true;
                    try {
                        java.lang.reflect.Method m = MeshShaderNative.class.getDeclaredMethod("updateNativeChunkMesh", long.class, long.class, java.nio.ByteBuffer.class, int.class, int.class, java.nio.ByteBuffer.class, int.class, int.class);
                        nativeUpdateSupported = (m != null);
                        MetalLogger.info("nativeUpdateSupported=" + nativeUpdateSupported);
                    } catch (NoSuchMethodException ignored) {
                        nativeUpdateSupported = false;
                        MetalLogger.info("updateNativeChunkMesh method not found");
                    }
                }
            } catch (Throwable t) {
                MetalLogger.error("initMeshPipeline failed: " + t);
                meshEnabled = false;
            }
        }
        initialized = true;
        MetalLogger.info("MeshShaderBackend initialized, meshEnabled=" + meshEnabled);
        return true;
    }

    public synchronized void destroy() {
        if (!initialized) return;
        
        meshUploadExecutor.shutdown();
        try {
            if (!meshUploadExecutor.awaitTermination(100, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                meshUploadExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            meshUploadExecutor.shutdownNow();
        }
        
        for (Long h : chunkHandles.values()) {
            if (h != null && h != 0L) {
                try { MeshShaderNative.destroyNativeChunkMesh(h); } catch (Throwable ignored) {}
            }
        }
        chunkHandles.clear();
        meshed.clear();
        Long ph;
        while ((ph = meshPool.poll()) != null) {
            try { MeshShaderNative.destroyNativeChunkMesh(ph); } catch (Throwable ignored) {}
            poolSize.decrementAndGet();
        }
        if (deviceHandle != 0L) {
            try { MeshShaderNative.destroyMeshDevice(deviceHandle); } catch (Throwable t) { MetalLogger.error("Error destroying mesh device"); }
            deviceHandle = 0L;
        }
        occlusionCuller.clearCache();
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
        if (vertexData == null || !vertexData.isDirect() || vertexCount <= 0) {
            return 0L;
        }
        try {
            long h = 0L;
            if (nativeUpdateSupported) {
                Long pooled = meshPool.poll();
                if (pooled != null && pooled != 0L) {
                    try {
                        h = MeshShaderNative.updateNativeChunkMesh(pooled, deviceHandle, vertexData, vertexCount, vertexStride, indexData, indexCount, indexType);
                    } catch (Throwable t) {
                        try { MeshShaderNative.destroyNativeChunkMesh(pooled); } catch (Throwable ignored) {}
                        poolSize.decrementAndGet();
                        h = 0L;
                    }
                }
            }
            if (h == 0L) {
                h = MeshShaderNative.createNativeChunkMesh(deviceHandle, vertexData, vertexCount, vertexStride,
                        indexData, indexCount, indexType);
            }
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
            if (poolSize.get() < MAX_POOL_SIZE) {
                meshPool.offer(h);
                poolSize.incrementAndGet();
            } else {
                try { MeshShaderNative.destroyNativeChunkMesh(h); } catch (Throwable ignored) {}
            }
        }
        meshed.remove(pos);
    }

    public void beginFrame() {
        if (!initialized || !meshEnabled) return;
        try {
            MeshShaderNative.startMeshFrame(deviceHandle);
            updateFrustumCulling();
        } catch (Throwable t) {
        }
    }
    
    private void updateFrustumCulling() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.gameRenderer == null) return;
        
        Camera camera = mc.gameRenderer.getCamera();
        if (camera == null) return;
        
        Window window = mc.getWindow();
        float aspect = (float) window.getFramebufferWidth() / window.getFramebufferHeight();
        float fovDegrees = (mc.options != null && mc.options.getFov() != null) ? 
                          (float) mc.options.getFov().getValue() : 70f;
        float fovRadians = (float) Math.toRadians(fovDegrees);
        float zNear = 0.05f;
        float zFar = 1000f;
        
        Matrix4f projectionMatrix = new Matrix4f();
        projectionMatrix.perspective(fovRadians, aspect, zNear, zFar);
        frustumCuller.updateFromCamera(camera, projectionMatrix);
    }
    
    public boolean shouldRenderChunk(BlockPos chunkPos) {
        if (!frustumCuller.isFrustumValid()) {
            return true; 
        }
        
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null && mc.world != null && mc.gameRenderer != null) {
            Camera camera = mc.gameRenderer.getCamera();
           
            int minY = mc.world.getBottomY();
            int maxY = mc.world.getHeight();
            if (!frustumCuller.isChunkVisible(chunkPos.getX() >> 4, chunkPos.getZ() >> 4, minY, maxY)) {
                return false; 
            }
           
            if (camera != null) {
                double distanceSquared = camera.getPos().squaredDistanceTo(
                    (chunkPos.getX() << 4) + 8, chunkPos.getY() + 32, (chunkPos.getZ() << 4) + 8);
                if (distanceSquared > (48 * 48)) { 
                    return !occlusionCuller.isChunkOccluded(chunkPos, camera);
                }
            }
            return true; 
        }
        
        return true; //safety 
    }
    
    public String getCullingStats() {
        return String.format("Frustum valid: %s, Occlusion cache size: %d", 
                           frustumCuller.isFrustumValid(), 
                           occlusionCuller.getCacheSize());
    }
    public void queueDrawChunkLayer(BlockPos pos, int layer) {
        drawQueue.add(new DrawRequest(pos, layer));
    }

   

    public void processDrawQueue() {
        if (!initialized || !meshEnabled) return;
        int processed = 0;
        int culled = 0;
        DrawRequest req;
        while ((req = drawQueue.poll()) != null) {
            if (!shouldRenderChunk(req.pos)) {
                culled++;
                continue;
            }
            
            Long h = chunkHandles.get(req.pos);
            if (h != null && h != 0L) {
                try {
                    MeshShaderNative.drawNativeChunkMesh(deviceHandle, h, req.layer);
                    processed++;
                } catch (Throwable t) {
                }
            }
        }
        
        if (culled > 0) {
            MetalLogger.info("Culled " + culled + " chunks (frustum + occlusion), rendered " + processed);
        }
    }
    public void endFrame() {
        if (!initialized || !meshEnabled) return;
        try {
            MeshShaderNative.endMeshFrame(deviceHandle);
        } catch (Throwable t) {
        }
    }
    public boolean isMeshEnabled() {
        return meshEnabled;
    }
     public boolean hasMesh(BlockPos pos) {
         return meshed.contains(pos);
     }
}
