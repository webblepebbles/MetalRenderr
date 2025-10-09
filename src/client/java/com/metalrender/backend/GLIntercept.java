package com.metalrender.backend;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Intercepts GL buffer binds/uploads and mirrors them into Metal buffers.
 * Also conditionally translates glDrawElements into Metal draws.
 */
public final class GLIntercept {
    private static final int GL_ARRAY_BUFFER = 0x8892;
    private static final int GL_ELEMENT_ARRAY_BUFFER = 0x8893;

    private static final ThreadLocal<Integer> boundVbo = ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<Integer> boundIbo = ThreadLocal.withInitial(() -> 0);

    // Map GL buffer id -> Metal handle
    private static final Map<Integer, Long> vboMap = new ConcurrentHashMap<>();
    private static final Map<Integer, Long> iboMap = new ConcurrentHashMap<>();

    // Simple no-op backend for fallback
    private static final RenderBackend NO_OP_BACKEND = new NoOpRenderBackend();

    // Backend singleton (lazy) - only initialized when MetalRender is actually enabled
    private static volatile RenderBackend backend;

    private static RenderBackend backend() {
        // Don't initialize backend if MetalRender is disabled
        if (!com.metalrender.config.MetalRenderConfig.mirrorUploads()) {
            return NO_OP_BACKEND;
        }

        RenderBackend b = backend;
        if (b == null) {
            synchronized (GLIntercept.class) {
                if (backend == null) {
                    try {
                        backend = new MetalRenderer();
                    } catch (Exception e) {
                        // If MetalRenderer fails to initialize, create a no-op backend
                        backend = NO_OP_BACKEND;
                    }
                }
                b = backend;
            }
        }
        return b;
    }

    // Simple no-op backend for fallback
    private static class NoOpRenderBackend implements RenderBackend {
        @Override
        public long createVertexBuffer(ByteBuffer data, int size, int stride) {
            return 0L;
        }
        @Override
        public long createIndexBuffer(ByteBuffer data, int size) {
            return 0L;
        }
        @Override
        public void destroyBuffer(long handle) {}
        @Override
        public void beginFrame(float[] viewProj) {}
        @Override
        public void drawIndexed(long vbo, long ibo, int indexCount, int firstIndex, int baseVertex) {}
        @Override
        public void endFrame() {}
    }

    public static void onBindBuffer(int target, int buffer) {
        // Early exit if MetalRender is disabled
        if (!com.metalrender.config.MetalRenderConfig.mirrorUploads())
            return;

        if (target == GL_ARRAY_BUFFER)
            boundVbo.set(buffer);
        else if (target == GL_ELEMENT_ARRAY_BUFFER)
            boundIbo.set(buffer);
    }

    public static void onBufferData(int target, ByteBuffer data, int usage, int guessedStride) {
        // Early exit if MetalRender is disabled - this is the hot path!
        if (!com.metalrender.config.MetalRenderConfig.mirrorUploads())
            return;

        // TODO: Re-enable when proper GL hooks are in place
        // Currently disabled to avoid interference with normal rendering
        /*
        if (data == null) return;
        if (target == GL_ARRAY_BUFFER) {
            int id = boundVbo.get();
            if (id != 0) {
                long handle = backend().createVertexBuffer(data, data.remaining(), Math.max(1, guessedStride));
                vboMap.put(id, handle);
            }
        } else if (target == GL_ELEMENT_ARRAY_BUFFER) {
            int id = boundIbo.get();
            if (id != 0) {
                long handle = backend().createIndexBuffer(data, data.remaining());
                iboMap.put(id, handle);
            }
        }
        */
    }

    public static void onDeleteBuffer(int id) {
        // Early exit if MetalRender is disabled
        if (!com.metalrender.config.MetalRenderConfig.mirrorUploads())
            return;

        Long v = vboMap.remove(id);
        if (v != null)
            backend().destroyBuffer(v);
        Long i = iboMap.remove(id);
        if (i != null)
            backend().destroyBuffer(i);
        // Clear bindings if they pointed to the deleted id
        if (boundVbo.get() == id)
            boundVbo.set(0);
        if (boundIbo.get() == id)
            boundIbo.set(0);
    }

    /**
     * Attempt to translate a glDrawElements into a Metal draw. Returns true if handled (skip GL), false to let GL
     * proceed.
     */
    public static boolean onDrawElements(int mode, int count, int type, long indicesOffset, float[] viewProj) {
        // Early exit if MetalRender draw swap is disabled - this is the hottest path!
        if (!com.metalrender.config.MetalRenderConfig.swapOpaque()
            && !com.metalrender.config.MetalRenderConfig.swapCutout()
            && !com.metalrender.config.MetalRenderConfig.swapTranslucent()) {
            return false;
        }

        // Only handle triangles
        if (mode != 0x0004 /*GL_TRIANGLES*/ || count <= 0)
            return false;
        int vboId = boundVbo.get();
        int iboId = boundIbo.get();
        Long vbo = vboMap.get(vboId);
        Long ibo = iboMap.get(iboId);
        if (vbo == null || ibo == null)
            return false;
        // Compute firstIndex from byte offset and index type
        // Compute firstIndex from byte offset and index type (kept for when Metal path is enabled)
        // int indexSize = (type == 0x1405 /*GL_UNSIGNED_INT*/ ? 4 : 2);
        // int firstIndex = (int)(indicesOffset / Math.max(1, indexSize));
        // Metal draw path not yet bound to a real render target; do not intercept yet.
        // We'll enable this once the native drawIndexed encodes into a valid MTLRenderPass.
        return false;
    }
}
