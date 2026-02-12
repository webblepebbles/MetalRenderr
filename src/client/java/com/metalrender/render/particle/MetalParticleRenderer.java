package com.metalrender.render.particle;

import com.metalrender.entity.EntityTextureCache;
import com.metalrender.nativebridge.EntityBatchBuilder;
import com.metalrender.nativebridge.NativeBridge;
import com.metalrender.util.MetalLogger;
import org.joml.Matrix4f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

public class MetalParticleRenderer {

    private static final MetalParticleRenderer INSTANCE = new MetalParticleRenderer();

    public static final int PARTICLE_VERTEX_STRIDE = 32;
    private static final int MAX_PARTICLES = 65536;
    private static final int INITIAL_CAPACITY = 8192;
    private long deviceHandle = 0;
    private final EntityBatchBuilder entityBatch = new EntityBatchBuilder();
    private volatile boolean enabled = false;
    private ByteBuffer vertexBuffer;
    private int particleCount = 0;
    private final List<ParticleMesh> pendingMeshes = new ArrayList<>();
    private String currentTexture = null;
    private final float[] viewProjection = new float[16];
    private final float[] cameraPos = new float[4];
    private float camRightX, camRightY, camRightZ;
    private float camUpX, camUpY, camUpZ;
    private int frameCount = 0;
    private int totalParticles = 0;

    private MetalParticleRenderer() {
        allocateBuffer(INITIAL_CAPACITY);
    }

    public static MetalParticleRenderer getInstance() {
        return INSTANCE;
    }

    private void allocateBuffer(int capacity) {
        vertexBuffer = ByteBuffer.allocateDirect(capacity * PARTICLE_VERTEX_STRIDE * 6)
                .order(ByteOrder.nativeOrder());
    }

    public void initialize(long device) {
        this.deviceHandle = device;
        this.enabled = true;
        MetalLogger.info("[MetalParticleRenderer] Initialized with device: {}", device);
    }

    public boolean isEnabled() {
        return enabled && deviceHandle != 0;
    }

    public void beginFrame(Matrix4f viewProj, Matrix4f viewMatrix, float camX, float camY, float camZ) {
        if (!enabled)
            return;

        frameCount++;
        totalParticles = 0;

        viewProj.get(viewProjection);
        cameraPos[0] = camX;
        cameraPos[1] = camY;
        cameraPos[2] = camZ;
        cameraPos[3] = 1.0f;
        camRightX = viewMatrix.m00();
        camRightY = viewMatrix.m10();
        camRightZ = viewMatrix.m20();

        camUpX = viewMatrix.m01();
        camUpY = viewMatrix.m11();
        camUpZ = viewMatrix.m21();
        vertexBuffer.clear();
        particleCount = 0;
        pendingMeshes.clear();
        currentTexture = null;
    }

    public void setTexture(String texture) {
        if (currentTexture != null && !currentTexture.equals(texture) && particleCount > 0) {
            flushMesh();
        }
        currentTexture = texture;
    }

    public void addParticle(float x, float y, float z,
            float size,
            float u1, float v1, float u2, float v2,
            int color) {
        if (!enabled)
            return;

        if (particleCount >= MAX_PARTICLES - 1) {
            flushMesh();
        }
        float halfSize = size * 0.5f;

        float rightX = camRightX * halfSize;
        float rightY = camRightY * halfSize;
        float rightZ = camRightZ * halfSize;

        float upX = camUpX * halfSize;
        float upY = camUpY * halfSize;
        float upZ = camUpZ * halfSize;
        float blX = x - rightX - upX;
        float blY = y - rightY - upY;
        float blZ = z - rightZ - upZ;
        float brX = x + rightX - upX;
        float brY = y + rightY - upY;
        float brZ = z + rightZ - upZ;
        float trX = x + rightX + upX;
        float trY = y + rightY + upY;
        float trZ = z + rightZ + upZ;
        float tlX = x - rightX + upX;
        float tlY = y - rightY + upY;
        float tlZ = z - rightZ + upZ;
        addVertex(blX, blY, blZ, u1, v2, color, size);
        addVertex(brX, brY, brZ, u2, v2, color, size);
        addVertex(trX, trY, trZ, u2, v1, color, size);
        addVertex(blX, blY, blZ, u1, v2, color, size);
        addVertex(trX, trY, trZ, u2, v1, color, size);
        addVertex(tlX, tlY, tlZ, u1, v1, color, size);

        particleCount++;
        totalParticles++;
    }

    public void addRotatedParticle(float x, float y, float z,
            float size, float rotation,
            float u1, float v1, float u2, float v2,
            int color) {
        if (!enabled)
            return;

        if (particleCount >= MAX_PARTICLES - 1) {
            flushMesh();
        }

        float halfSize = size * 0.5f;
        float cos = (float) Math.cos(rotation);
        float sin = (float) Math.sin(rotation);
        float rx = camRightX * cos - camUpX * sin;
        float ry = camRightY * cos - camUpY * sin;
        float rz = camRightZ * cos - camUpZ * sin;

        float ux = camRightX * sin + camUpX * cos;
        float uy = camRightY * sin + camUpY * cos;
        float uz = camRightZ * sin + camUpZ * cos;

        rx *= halfSize;
        ry *= halfSize;
        rz *= halfSize;
        ux *= halfSize;
        uy *= halfSize;
        uz *= halfSize;
        float blX = x - rx - ux;
        float blY = y - ry - uy;
        float blZ = z - rz - uz;

        float brX = x + rx - ux;
        float brY = y + ry - uy;
        float brZ = z + rz - uz;

        float trX = x + rx + ux;
        float trY = y + ry + uy;
        float trZ = z + rz + uz;

        float tlX = x - rx + ux;
        float tlY = y - ry + uy;
        float tlZ = z - rz + uz;
        addVertex(blX, blY, blZ, u1, v2, color, size);
        addVertex(brX, brY, brZ, u2, v2, color, size);
        addVertex(trX, trY, trZ, u2, v1, color, size);

        addVertex(blX, blY, blZ, u1, v2, color, size);
        addVertex(trX, trY, trZ, u2, v1, color, size);
        addVertex(tlX, tlY, tlZ, u1, v1, color, size);

        particleCount++;
        totalParticles++;
    }

    private void addVertex(float x, float y, float z, float u, float v, int color, float size) {
        ensureCapacity();
        vertexBuffer.putFloat(x);
        vertexBuffer.putFloat(y);
        vertexBuffer.putFloat(z);
        vertexBuffer.putFloat(u);
        vertexBuffer.putFloat(v);
        vertexBuffer.putInt(color);
        vertexBuffer.putFloat(size);
        vertexBuffer.putInt(0);
    }

    private void ensureCapacity() {
        if (vertexBuffer.remaining() < PARTICLE_VERTEX_STRIDE) {
            ByteBuffer newBuffer = ByteBuffer.allocateDirect(vertexBuffer.capacity() * 2)
                    .order(ByteOrder.nativeOrder());
            vertexBuffer.flip();
            newBuffer.put(vertexBuffer);
            vertexBuffer = newBuffer;
        }
    }

    public void flushMesh() {
        if (particleCount == 0)
            return;

        vertexBuffer.flip();
        ByteBuffer copy = ByteBuffer.allocateDirect(vertexBuffer.remaining())
                .order(ByteOrder.nativeOrder());
        copy.put(vertexBuffer);
        copy.flip();
        pendingMeshes.add(new ParticleMesh(copy, particleCount * 6, currentTexture));

        vertexBuffer.clear();
        particleCount = 0;
    }

    public void endFrame() {
        if (!enabled || deviceHandle == 0)
            return;

        flushMesh();

        if (pendingMeshes.isEmpty())
            return;

        try {
            NativeBridge.nBeginEntityPass(deviceHandle, viewProjection, cameraPos);

            entityBatch.begin();
            for (ParticleMesh mesh : pendingMeshes) {
                if (mesh.vertexCount <= 0)
                    continue;

                long textureHandle = 0;
                if (mesh.textureId != null) {
                    textureHandle = EntityTextureCache.getInstance().getOrCreateTexture(mesh.textureId);
                }
                entityBatch.addMesh(mesh.vertexData, mesh.vertexCount, textureHandle);
            }
            entityBatch.submit(deviceHandle);

            NativeBridge.nEndEntityPass(deviceHandle);

            if (frameCount <= 10 || frameCount % 300 == 0) {
                MetalLogger.info("[MetalParticleRenderer] Frame {}: {} particles",
                        frameCount, totalParticles);
            }

        } catch (UnsatisfiedLinkError e) {
            if (frameCount <= 5) {
                MetalLogger.warn("[MetalParticleRenderer] Native not available: {}", e.getMessage());
            }
        }

        pendingMeshes.clear();
    }

    public int getTotalParticles() {
        return totalParticles;
    }

    public void destroy() {
        enabled = false;
        vertexBuffer.clear();
        pendingMeshes.clear();
        deviceHandle = 0;
    }

    public static class ParticleMesh {
        public final ByteBuffer vertexData;
        public final int vertexCount;
        public final String textureId;

        public ParticleMesh(ByteBuffer data, int count, String texture) {
            this.vertexData = data;
            this.vertexCount = count;
            this.textureId = texture;
        }
    }
}
