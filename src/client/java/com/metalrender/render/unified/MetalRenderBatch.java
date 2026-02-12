package com.metalrender.render.unified;

import com.metalrender.entity.EntityTextureCache;
import com.metalrender.nativebridge.EntityBatchBuilder;
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

    public static final int STANDARD_VERTEX_STRIDE = 32;

    public static final int GUI_VERTEX_STRIDE = 20;

    private static final int MAX_VERTICES_PER_BATCH = 65536;

    private static final int INITIAL_CAPACITY = 8192;

    private static final MetalRenderBatch INSTANCE = new MetalRenderBatch();

    private final Map<MetalRenderType, RenderBatch> batches = new ConcurrentHashMap<>();

    private final Matrix4f currentModelMatrix = new Matrix4f();
    private final Matrix3f currentNormalMatrix = new Matrix3f();

    private final float[] viewProjection = new float[16];
    private final float[] cameraPos = new float[4];

    private long deviceHandle = 0;
    private final EntityBatchBuilder entityBatch = new EntityBatchBuilder();

    private int totalVerticesThisFrame = 0;
    private int totalBatchesThisFrame = 0;
    private int frameCount = 0;

    private MetalRenderBatch() {
        for (MetalRenderType type : MetalRenderType.values()) {
            batches.put(type, new RenderBatch(type));
        }
    }

    public static MetalRenderBatch getInstance() {
        return INSTANCE;
    }

    public void initialize(long device) {
        this.deviceHandle = device;
        MetalLogger.info("[MetalRenderBatch] Initialized with device handle: {}", device);
    }

    public void beginFrame(Matrix4f viewProj, float camX, float camY, float camZ) {
        frameCount++;
        totalVerticesThisFrame = 0;
        totalBatchesThisFrame = 0;
        viewProj.get(viewProjection);
        cameraPos[0] = camX;
        cameraPos[1] = camY;
        cameraPos[2] = camZ;
        cameraPos[3] = 1.0f;
        for (RenderBatch batch : batches.values()) {
            batch.reset();
        }
        currentModelMatrix.identity();
        currentNormalMatrix.identity();
    }

    public void setTransform(Matrix4f model, Matrix3f normal) {
        currentModelMatrix.set(model);
        currentNormalMatrix.set(normal);
    }

    public void setTransform(Matrix4f model) {
        currentModelMatrix.set(model);
        currentNormalMatrix.set(
                model.m00(), model.m01(), model.m02(),
                model.m10(), model.m11(), model.m12(),
                model.m20(), model.m21(), model.m22());
        currentNormalMatrix.invert().transpose();
    }

    public RenderBatch getBatch(MetalRenderType type) {
        return batches.get(type);
    }

    public void addVertex(MetalRenderType type,
            float x, float y, float z,
            float u, float v,
            int color,
            float nx, float ny, float nz,
            String textureId) {
        RenderBatch batch = batches.get(type);
        if (batch == null)
            return;
        Vector4f pos = new Vector4f(x, y, z, 1.0f);
        pos.mul(currentModelMatrix);
        Vector3f normal = new Vector3f(nx, ny, nz);
        normal.mul(currentNormalMatrix);
        normal.normalize();

        batch.addVertex(pos.x, pos.y, pos.z, u, v, color, normal.x, normal.y, normal.z, textureId);
    }

    public void addGuiVertex(float x, float y, float z, float u, float v, int color, String textureId) {
        RenderBatch batch = batches.get(MetalRenderType.GUI);
        if (batch == null)
            return;
        batch.addGuiVertex(x, y, z, u, v, color, textureId);
    }

    public void flush() {
        if (deviceHandle == 0)
            return;
        List<MetalRenderType> sortedTypes = new ArrayList<>(batches.keySet());
        sortedTypes.sort(Comparator.comparingInt(MetalRenderType::priority));
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

    private void renderBatch(RenderBatch batch) {
        List<BatchedMesh> meshes = batch.harvest();
        if (meshes.isEmpty())
            return;

        try {
            NativeBridge.nBeginEntityPass(deviceHandle, viewProjection, cameraPos);

            entityBatch.begin();
            for (BatchedMesh mesh : meshes) {
                if (mesh.vertexCount <= 0)
                    continue;
                long textureHandle = 0;
                if (mesh.textureId != null) {
                    textureHandle = EntityTextureCache.getInstance().getOrCreateTexture(mesh.textureId);
                }

                entityBatch.addMesh(mesh.vertexData, mesh.vertexCount, textureHandle);

                totalVerticesThisFrame += mesh.vertexCount;
                totalBatchesThisFrame++;
            }
            entityBatch.submit(deviceHandle);

            NativeBridge.nEndEntityPass(deviceHandle);

        } catch (UnsatisfiedLinkError e) {
            if (frameCount <= 5) {
                MetalLogger.warn("[MetalRenderBatch] Native rendering not available: {}", e.getMessage());
            }
        }
    }

    public int getTotalVerticesThisFrame() {
        return totalVerticesThisFrame;
    }

    public int getTotalBatchesThisFrame() {
        return totalBatchesThisFrame;
    }

    public void destroy() {
        for (RenderBatch batch : batches.values()) {
            batch.reset();
        }
        deviceHandle = 0;
    }

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
            currentBuffer.putFloat(x);
            currentBuffer.putFloat(y);
            currentBuffer.putFloat(z);
            currentBuffer.putFloat(u);
            currentBuffer.putFloat(v);
            currentBuffer.putInt(color);
            int packedNormal = packNormal(nx, ny, nz);
            currentBuffer.putInt(packedNormal);
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
            currentBuffer.putFloat(x);
            currentBuffer.putFloat(y);
            currentBuffer.putFloat(z);
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
