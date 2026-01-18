package com.metalrender.render.unified;

import com.metalrender.entity.EntityTextureCache;
import com.metalrender.nativebridge.NativeBridge;
import com.metalrender.util.MetalLogger;
import net.minecraft.util.Identifier;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class MetalRenderBatch {

    /** Vertex stride for standard render (pos + uv + color + normal) = 32 bytes */
    public static final int STANDARD_VERTEX_STRIDE = 32;

    /** Vertex stride for GUI render (pos + uv + color) = 20 bytes */
    public static final int GUI_VERTEX_STRIDE = 20;

    /** Maximum vertices per batch */
    private static final int MAX_VERTICES_PER_BATCH = 65536;

    /** Initial buffer capacity in vertices */
    private static final int INITIAL_CAPACITY = 8192;

    /** Singleton instance */
    private static final MetalRenderBatch INSTANCE = new MetalRenderBatch();

    /** Batches by render type */
    private final Map<MetalRenderType, RenderBatch> batches = new ConcurrentHashMap<>();

    /** Current transform matrices */
    private final Matrix4f currentModelMatrix = new Matrix4f();
    private final Matrix3f currentNormalMatrix = new Matrix3f();

    /** Frame uniforms */
    private final float[] viewProjection = new float[16];
    private final float[] cameraPos = new float[4];

    /** Native device handle */
    private long deviceHandle = 0;

    /** Frame statistics */
    private int totalVerticesThisFrame = 0;
    private int totalBatchesThisFrame = 0;
    private int frameCount = 0;

    private MetalRenderBatch() {
        // Initialize batches for all render types
        for (MetalRenderType type : MetalRenderType.values()) {
            batches.put(type, new RenderBatch(type));
        }
    }

    public static MetalRenderBatch getInstance() {
        return INSTANCE;
    }

    /**
     * Initialize with the Metal device handle.
     */
    public void initialize(long device) {
        this.deviceHandle = device;
        MetalLogger.info("[MetalRenderBatch] Initialized with device handle: {}", device);
    }

    /**
     * Begin a new render frame.
     */
    public void beginFrame(Matrix4f viewProj, float camX, float camY, float camZ) {
        frameCount++;
        totalVerticesThisFrame = 0;
        totalBatchesThisFrame = 0;

        // Store frame uniforms
        viewProj.get(viewProjection);
        cameraPos[0] = camX;
        cameraPos[1] = camY;
        cameraPos[2] = camZ;
        cameraPos[3] = 1.0f;

        // Reset all batches
        for (RenderBatch batch : batches.values()) {
            batch.reset();
        }

        // Reset transforms
        currentModelMatrix.identity();
        currentNormalMatrix.identity();
    }

    /**
     * Set the model transform for subsequent vertices.
     */
    public void setTransform(Matrix4f model, Matrix3f normal) {
        currentModelMatrix.set(model);
        currentNormalMatrix.set(normal);
    }

    /**
     * Set the model transform from a position matrix only.
     */
    public void setTransform(Matrix4f model) {
        currentModelMatrix.set(model);
        // Extract 3x3 rotation/scale for normal matrix
        currentNormalMatrix.set(
                model.m00(), model.m01(), model.m02(),
                model.m10(), model.m11(), model.m12(),
                model.m20(), model.m21(), model.m22());
        currentNormalMatrix.invert().transpose();
    }

    /**
     * Get the batch for a specific render type.
     */
    public RenderBatch getBatch(MetalRenderType type) {
        return batches.get(type);
    }

    /**
     * Add a vertex to the specified render type.
     * Standard vertex format: pos (12) + uv (8) + color (4) + normal (4) + pad (4)
     * = 32 bytes
     */
    public void addVertex(MetalRenderType type,
            float x, float y, float z,
            float u, float v,
            int color,
            float nx, float ny, float nz,
            String textureId) {
        RenderBatch batch = batches.get(type);
        if (batch == null)
            return;

        // Transform position
        Vector4f pos = new Vector4f(x, y, z, 1.0f);
        pos.mul(currentModelMatrix);

        // Transform normal
        Vector3f normal = new Vector3f(nx, ny, nz);
        normal.mul(currentNormalMatrix);
        normal.normalize();

        batch.addVertex(pos.x, pos.y, pos.z, u, v, color, normal.x, normal.y, normal.z, textureId);
    }

    /**
     * Add a GUI vertex (no transform, no normal).
     * GUI vertex format: pos (12) + uv (8) = 20 bytes
     */
    public void addGuiVertex(float x, float y, float z, float u, float v, int color, String textureId) {
        RenderBatch batch = batches.get(MetalRenderType.GUI);
        if (batch == null)
            return;
        batch.addGuiVertex(x, y, z, u, v, color, textureId);
    }

    /**
     * Flush all batches and render to Metal.
     */
    public void flush() {
        if (deviceHandle == 0)
            return;

        // Sort render types by priority
        List<MetalRenderType> sortedTypes = new ArrayList<>(batches.keySet());
        sortedTypes.sort(Comparator.comparingInt(MetalRenderType::priority));

        // Render each batch in order
        for (MetalRenderType type : sortedTypes) {
            RenderBatch batch = batches.get(type);
            if (batch != null && batch.hasData()) {
                renderBatch(batch);
            }
        }

        if (frameCount <= 10 || frameCount % 300 == 0) {
            MetalLogger.info("[MetalRenderBatch] Frame {} flushed: {} vertices in {} batches",
                    frameCount, totalVerticesThisFrame, totalBatchesThisFrame);
        }
    }

    /**
     * Render a single batch to Metal.
     */
    private void renderBatch(RenderBatch batch) {
        List<BatchedMesh> meshes = batch.harvest();
        if (meshes.isEmpty())
            return;

        try {
            // Begin render pass for this type
            NativeBridge.nBeginEntityPass(deviceHandle, viewProjection, cameraPos);

            for (BatchedMesh mesh : meshes) {
                if (mesh.vertexCount <= 0)
                    continue;

                // Get texture handle if needed
                long textureHandle = 0;
                if (mesh.textureId != null) {
                    textureHandle = EntityTextureCache.getInstance().getOrCreateTexture(mesh.textureId);
                }

                NativeBridge.nDrawEntity(deviceHandle, mesh.vertexData, mesh.vertexCount, textureHandle);

                totalVerticesThisFrame += mesh.vertexCount;
                totalBatchesThisFrame++;
            }

            NativeBridge.nEndEntityPass(deviceHandle);

        } catch (UnsatisfiedLinkError e) {
            // Native not available
            if (frameCount <= 5) {
                MetalLogger.warn("[MetalRenderBatch] Native rendering not available: {}", e.getMessage());
            }
        }
    }

    /**
     * Get frame statistics.
     */
    public int getTotalVerticesThisFrame() {
        return totalVerticesThisFrame;
    }

    public int getTotalBatchesThisFrame() {
        return totalBatchesThisFrame;
    }

    /**
     * Clean up resources.
     */
    public void destroy() {
        for (RenderBatch batch : batches.values()) {
            batch.reset();
        }
        deviceHandle = 0;
    }

    // ========================================================================
    // Inner Classes
    // ========================================================================

    /**
     * A render batch for a specific render type.
     */
    public static class RenderBatch {
        private final MetalRenderType type;
        private final List<BatchedMesh> meshes = new ArrayList<>();
        private ByteBuffer currentBuffer;
        private int vertexCount = 0;
        private String currentTexture = null;

        public RenderBatch(MetalRenderType type) {
            this.type = type;
            allocateBuffer(INITIAL_CAPACITY);
        }

        private void allocateBuffer(int capacity) {
            int stride = type == MetalRenderType.GUI || type == MetalRenderType.GUI_TEXT
                    ? GUI_VERTEX_STRIDE
                    : STANDARD_VERTEX_STRIDE;
            currentBuffer = ByteBuffer.allocateDirect(capacity * stride)
                    .order(ByteOrder.nativeOrder());
        }

        public void reset() {
            meshes.clear();
            currentBuffer.clear();
            vertexCount = 0;
            currentTexture = null;
        }

        public void setTexture(String texture) {
            if (currentTexture != null && !currentTexture.equals(texture) && vertexCount > 0) {
                flushMesh();
            }
            currentTexture = texture;
        }

        public void addVertex(float x, float y, float z,
                float u, float v,
                int color,
                float nx, float ny, float nz,
                String textureId) {
            if (!Objects.equals(textureId, currentTexture)) {
                setTexture(textureId);
            }

            if (vertexCount >= MAX_VERTICES_PER_BATCH - 1) {
                flushMesh();
            }

            ensureCapacity(STANDARD_VERTEX_STRIDE);

            // Write position (12 bytes)
            currentBuffer.putFloat(x);
            currentBuffer.putFloat(y);
            currentBuffer.putFloat(z);

            // Write UV (8 bytes)
            currentBuffer.putFloat(u);
            currentBuffer.putFloat(v);

            // Write color RGBA (4 bytes)
            currentBuffer.putInt(color);

            // Write normal packed 10-10-10-2 (4 bytes)
            int packedNormal = packNormal(nx, ny, nz);
            currentBuffer.putInt(packedNormal);

            // Padding (4 bytes)
            currentBuffer.putInt(0);

            vertexCount++;
        }

        public void addGuiVertex(float x, float y, float z,
                float u, float v, int color,
                String textureId) {
            if (!Objects.equals(textureId, currentTexture)) {
                setTexture(textureId);
            }

            if (vertexCount >= MAX_VERTICES_PER_BATCH - 1) {
                flushMesh();
            }

            ensureCapacity(GUI_VERTEX_STRIDE);

            // Write position (12 bytes)
            currentBuffer.putFloat(x);
            currentBuffer.putFloat(y);
            currentBuffer.putFloat(z);

            // Write UV (8 bytes) - skip if text
            currentBuffer.putFloat(u);
            currentBuffer.putFloat(v);

            vertexCount++;
        }

        private void ensureCapacity(int bytesNeeded) {
            if (currentBuffer.remaining() < bytesNeeded) {
                int newCapacity = currentBuffer.capacity() * 2;
                ByteBuffer newBuffer = ByteBuffer.allocateDirect(newCapacity)
                        .order(ByteOrder.nativeOrder());
                currentBuffer.flip();
                newBuffer.put(currentBuffer);
                currentBuffer = newBuffer;
            }
        }

        private int packNormal(float x, float y, float z) {
            int nx = (int) ((x * 0.5f + 0.5f) * 1023.0f) & 0x3FF;
            int ny = (int) ((y * 0.5f + 0.5f) * 1023.0f) & 0x3FF;
            int nz = (int) ((z * 0.5f + 0.5f) * 1023.0f) & 0x3FF;
            return nx | (ny << 10) | (nz << 20);
        }

        public void flushMesh() {
            if (vertexCount == 0)
                return;

            currentBuffer.flip();
            ByteBuffer copy = ByteBuffer.allocateDirect(currentBuffer.remaining())
                    .order(ByteOrder.nativeOrder());
            copy.put(currentBuffer);
            copy.flip();

            meshes.add(new BatchedMesh(copy, vertexCount, currentTexture, type));

            currentBuffer.clear();
            vertexCount = 0;
        }

        public List<BatchedMesh> harvest() {
            flushMesh();
            List<BatchedMesh> result = new ArrayList<>(meshes);
            meshes.clear();
            return result;
        }

        public boolean hasData() {
            return vertexCount > 0 || !meshes.isEmpty();
        }

        public MetalRenderType getType() {
            return type;
        }
    }

    /**
     * A batched mesh ready for upload to Metal.
     */
    public static class BatchedMesh {
        public final ByteBuffer vertexData;
        public final int vertexCount;
        public final String textureId;
        public final MetalRenderType renderType;

        public BatchedMesh(ByteBuffer data, int count, String texture, MetalRenderType type) {
            this.vertexData = data;
            this.vertexCount = count;
            this.textureId = texture;
            this.renderType = type;
        }
    }
}
