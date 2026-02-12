package com.metalrender.entity;

import net.minecraft.client.render.VertexConsumer;
import org.joml.Matrix4f;
import org.joml.Matrix3f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

public class MetalVertexConsumer implements VertexConsumer {

    public static final int VERTEX_STRIDE = 32;

    
    private static final int INITIAL_CAPACITY = 4096;

    
    private static final int MAX_VERTICES = 65536;
    private float posX, posY, posZ;
    private float u, v;
    private int color = 0xFFFFFFFF; 
    private int packedNormal = 0x007F7F7F; 
    private int packedLight = 0x00F000F0; 
    private boolean hasVertex = false; 
    private final Matrix4f positionMatrix = new Matrix4f();
    private final Matrix3f normalMatrix = new Matrix3f();
    private final float[][] quadVertices = new float[4][8]; 
    private int quadVertexIndex = 0; 
    private boolean lineMode = false;
    private final float[][] lineVertices = new float[2][8]; 
    private int lineVertexIndex = 0;
    private static final float LINE_WIDTH = 0.005f; 
    private ByteBuffer buffer;
    private int vertexCount = 0;
    private final List<CapturedMesh> capturedMeshes = new ArrayList<>();
    private String currentTexture = null;
    private static int totalVerticesCaptured = 0;

    public MetalVertexConsumer() {
        allocateBuffer(INITIAL_CAPACITY);
    }

    private void allocateBuffer(int vertexCapacity) {
        int bytes = vertexCapacity * VERTEX_STRIDE;
        buffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
        buffer.clear();
    }

    
    public void setLineMode(boolean enabled) {
        this.lineMode = enabled;
        this.lineVertexIndex = 0;
    }

    
    public int getCapturedMeshCount() {
        return capturedMeshes.size();
    }

    
    public void setTransforms(Matrix4f position, Matrix3f normal) {
        this.positionMatrix.set(position);
        this.normalMatrix.set(normal);
    }

    
    public void setTexture(String texture) {
        if (currentTexture != null && !currentTexture.equals(texture) && vertexCount > 0) {
            flushMesh();
        }
        this.currentTexture = texture;
    }

    
    public String getCurrentTexture() {
        return this.currentTexture;
    }

    
    public void flushMesh() {
        if (hasVertex) {
            emitVertex();
            hasVertex = false;
        }

        if (vertexCount == 0) {
            return;
        }

        buffer.flip();
        ByteBuffer copy = ByteBuffer.allocateDirect(buffer.remaining()).order(ByteOrder.nativeOrder());
        copy.put(buffer);
        copy.flip();

        capturedMeshes.add(new CapturedMesh(copy, vertexCount, currentTexture));
        totalVerticesCaptured += vertexCount;
        buffer.clear();
        vertexCount = 0;
        quadVertexIndex = 0; 
    }

    public List<CapturedMesh> harvest() {
        flushMesh();
        List<CapturedMesh> result = new ArrayList<>(capturedMeshes);
        capturedMeshes.clear();
        currentTexture = null;
        return result;
    }

    public void beginFrame() {
        if (hasVertex) {
            emitVertex();
        }
        buffer.clear();
        vertexCount = 0;
        capturedMeshes.clear();
        currentTexture = null;
        positionMatrix.identity();
        normalMatrix.identity();
        hasVertex = false;
        quadVertexIndex = 0; 
        lineMode = false; 
        lineVertexIndex = 0; 
    }

    @Override
    public VertexConsumer vertex(float x, float y, float z) {

        if (hasVertex) {
            emitVertex();
        }

        this.posX = x;
        this.posY = y;
        this.posZ = z;
        this.hasVertex = true;

        return this;
    }

    
    public VertexConsumer addVertex(float x, float y, float z) {
        return vertex(x, y, z);
    }

    @Override
    public VertexConsumer color(int red, int green, int blue, int alpha) {
        this.color = (alpha << 24) | (blue << 16) | (green << 8) | red;
        return this;
    }

    @Override
    public VertexConsumer color(int argb) {
        int a = (argb >> 24) & 0xFF;
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        this.color = (a << 24) | (b << 16) | (g << 8) | r;
        return this;
    }

    @Override
    public VertexConsumer texture(float u, float v) {
        this.u = u;
        this.v = v;
        return this;
    }

    @Override
    public VertexConsumer overlay(int u, int v) {
        return this;
    }

    @Override
    public VertexConsumer light(int u, int v) {
        this.packedLight = ((v & 0xFFFF) << 16) | (u & 0xFFFF);
        return this;
    }

    @Override
    public VertexConsumer normal(float x, float y, float z) {
        Vector3f n = new Vector3f(x, y, z);
        n.mul(normalMatrix);
        n.normalize();
        int nx = (int) ((n.x * 0.5f + 0.5f) * 1023.0f) & 0x3FF;
        int ny = (int) ((n.y * 0.5f + 0.5f) * 1023.0f) & 0x3FF;
        int nz = (int) ((n.z * 0.5f + 0.5f) * 1023.0f) & 0x3FF;
        this.packedNormal = nx | (ny << 10) | (nz << 20);
        return this;
    }

    @Override
    public VertexConsumer lineWidth(float width) {
        return this;
    }

    
    private void emitVertex() {
        float transformedX = posX;
        float transformedY = posY;
        float transformedZ = posZ;

        if (lineMode) {
            lineVertices[lineVertexIndex][0] = transformedX;
            lineVertices[lineVertexIndex][1] = transformedY;
            lineVertices[lineVertexIndex][2] = transformedZ;
            lineVertices[lineVertexIndex][3] = u;
            lineVertices[lineVertexIndex][4] = v;
            lineVertices[lineVertexIndex][5] = Float.intBitsToFloat(color);
            lineVertices[lineVertexIndex][6] = Float.intBitsToFloat(packedNormal);
            lineVertices[lineVertexIndex][7] = Float.intBitsToFloat(packedLight);

            lineVertexIndex++;

            if (lineVertexIndex >= 2) {
                emitLineAsQuad();
                lineVertexIndex = 0;
            }

            resetVertex();
            return;
        }
        quadVertices[quadVertexIndex][0] = transformedX;
        quadVertices[quadVertexIndex][1] = transformedY;
        quadVertices[quadVertexIndex][2] = transformedZ;
        quadVertices[quadVertexIndex][3] = u;
        quadVertices[quadVertexIndex][4] = v;
        quadVertices[quadVertexIndex][5] = Float.intBitsToFloat(color);
        quadVertices[quadVertexIndex][6] = Float.intBitsToFloat(packedNormal);
        quadVertices[quadVertexIndex][7] = Float.intBitsToFloat(packedLight);

        quadVertexIndex++;
        if (quadVertexIndex >= 4) {
            emitQuadAsTriangles();
            quadVertexIndex = 0;
        }
        resetVertex();
    }

    
    private void emitLineAsQuad() {
        if (vertexCount >= MAX_VERTICES - 6) {
            flushMesh();
        }
        int neededBytes = 6 * VERTEX_STRIDE;
        if (buffer.remaining() < neededBytes) {
            ByteBuffer newBuffer = ByteBuffer.allocateDirect(buffer.capacity() * 2)
                    .order(ByteOrder.nativeOrder());
            buffer.flip();
            newBuffer.put(buffer);
            buffer = newBuffer;
        }
        float x1 = lineVertices[0][0], y1 = lineVertices[0][1], z1 = lineVertices[0][2];
        float x2 = lineVertices[1][0], y2 = lineVertices[1][1], z2 = lineVertices[1][2];
        float dx = x2 - x1;
        float dy = y2 - y1;
        float dz = z2 - z1;
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 0.0001f)
            return; 
        dx /= len;
        dy /= len;
        dz /= len;
        float px, py, pz;
        if (Math.abs(dy) < 0.99f) {
            px = dz;
            py = 0;
            pz = -dx;
        } else {
            px = 0;
            py = -dz;
            pz = dy;
        }
        float plen = (float) Math.sqrt(px * px + py * py + pz * pz);
        if (plen > 0.0001f) {
            px /= plen;
            py /= plen;
            pz /= plen;
        }
        px *= LINE_WIDTH;
        py *= LINE_WIDTH;
        pz *= LINE_WIDTH;
        float[] v0 = { x1 - px, y1 - py, z1 - pz, lineVertices[0][3], lineVertices[0][4],
                lineVertices[0][5], lineVertices[0][6], 0 };
        float[] v1 = { x1 + px, y1 + py, z1 + pz, lineVertices[0][3], lineVertices[0][4],
                lineVertices[0][5], lineVertices[0][6], 0 };
        float[] v2 = { x2 + px, y2 + py, z2 + pz, lineVertices[1][3], lineVertices[1][4],
                lineVertices[1][5], lineVertices[1][6], 0 };
        float[] v3 = { x2 - px, y2 - py, z2 - pz, lineVertices[1][3], lineVertices[1][4],
                lineVertices[1][5], lineVertices[1][6], 0 };
        writeVertexToBuffer(v0);
        writeVertexToBuffer(v1);
        writeVertexToBuffer(v2);

        writeVertexToBuffer(v0);
        writeVertexToBuffer(v2);
        writeVertexToBuffer(v3);

        vertexCount += 6;
    }

    
    private void emitQuadAsTriangles() {
        if (vertexCount >= MAX_VERTICES - 6) {
            flushMesh();
        }
        int neededBytes = 6 * VERTEX_STRIDE;
        if (buffer.remaining() < neededBytes) {
            ByteBuffer newBuffer = ByteBuffer.allocateDirect(buffer.capacity() * 2)
                    .order(ByteOrder.nativeOrder());
            buffer.flip();
            newBuffer.put(buffer);
            buffer = newBuffer;
        }
        writeVertexToBuffer(quadVertices[0]);
        writeVertexToBuffer(quadVertices[1]);
        writeVertexToBuffer(quadVertices[2]);
        writeVertexToBuffer(quadVertices[0]);
        writeVertexToBuffer(quadVertices[2]);
        writeVertexToBuffer(quadVertices[3]);

        vertexCount += 6;
    }

    
    private void writeVertexToBuffer(float[] v) {
        buffer.putFloat(v[0]);
        buffer.putFloat(v[1]);
        buffer.putFloat(v[2]);
        buffer.putFloat(v[3]);
        buffer.putFloat(v[4]);
        buffer.putInt(Float.floatToRawIntBits(v[5]));
        buffer.putInt(Float.floatToRawIntBits(v[6]));
        buffer.putInt(Float.floatToRawIntBits(v[7]));
    }

    private void resetVertex() {
        posX = posY = posZ = 0;
        u = v = 0;
        color = 0xFFFFFFFF;
        packedNormal = 0x007F7F7F;
        packedLight = 0x00F000F0; 
        hasVertex = false;
    }

    public static int getTotalVerticesCaptured() {
        return totalVerticesCaptured;
    }

    public static void resetStats() {
        totalVerticesCaptured = 0;
    }

    public int getCurrentVertexCount() {
        return vertexCount;
    }

    public int getPendingMeshCount() {
        return capturedMeshes.size();
    }

    
    public static class CapturedMesh {
        public final ByteBuffer vertexData;
        public final int vertexCount;
        public final String textureId;

        public CapturedMesh(ByteBuffer data, int count, String texture) {
            this.vertexData = data;
            this.vertexCount = count;
            this.textureId = texture;
        }

        public int getByteSize() {
            return vertexCount * VERTEX_STRIDE;
        }
    }
}
