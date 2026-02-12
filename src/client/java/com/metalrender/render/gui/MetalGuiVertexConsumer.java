package com.metalrender.render.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexConsumer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

public class MetalGuiVertexConsumer implements VertexConsumer {

    private static final MetalGuiVertexConsumer INSTANCE = new MetalGuiVertexConsumer();
    public static final int VERTEX_STRIDE = 32;

    private static final int MAX_VERTICES = 65536;

    
    private ByteBuffer vertexBuffer;
    private int vertexCount = 0;

    
    private final List<CapturedMesh> pendingMeshes = new ArrayList<>();

    
    private String currentTexture = null;

    
    private float posX, posY, posZ;
    private float texU, texV;
    private int packedColor = 0xFFFFFFFF;
    private boolean hasVertex = false;

    
    private final float[][] quadVertices = new float[4][8]; 
    private int quadVertexIndex = 0;

    private MetalGuiVertexConsumer() {
        vertexBuffer = ByteBuffer.allocateDirect(MAX_VERTICES * VERTEX_STRIDE)
                .order(ByteOrder.nativeOrder());
    }

    public static MetalGuiVertexConsumer getInstance() {
        return INSTANCE;
    }

    
    public void setTexture(String texture) {
        if (currentTexture != null && !currentTexture.equals(texture) && vertexCount > 0) {
            flushMesh();
        }
        currentTexture = texture;
    }

    
    public String getCurrentTexture() {
        return currentTexture;
    }

    
    public void beginFrame() {
        vertexBuffer.clear();
        vertexCount = 0;
        pendingMeshes.clear();
        currentTexture = null;
        quadVertexIndex = 0;
        hasVertex = false;
    }

    
    public List<CapturedMesh> getCapturedMeshes() {
        flushMesh();
        List<CapturedMesh> result = new ArrayList<>(pendingMeshes);
        pendingMeshes.clear();
        return result;
    }

    
    private void flushMesh() {
        if (vertexCount == 0)
            return;

        vertexBuffer.flip();
        ByteBuffer copy = ByteBuffer.allocateDirect(vertexBuffer.remaining())
                .order(ByteOrder.nativeOrder());
        copy.put(vertexBuffer);
        copy.flip();

        pendingMeshes.add(new CapturedMesh(copy, vertexCount, currentTexture));

        vertexBuffer.clear();
        vertexCount = 0;
    }

    private static int debugCount = 0;

    @Override
    public VertexConsumer vertex(float x, float y, float z) {
        if (hasVertex) {
            emitVertex();
        }
        if (debugCount < 20) {
            debugCount++;
            com.metalrender.util.MetalLogger.info("[GuiTextVertex] vertex({}, {}, {})", x, y, z);
        }

        posX = x;
        posY = y;
        posZ = z;
        hasVertex = true;
        return this;
    }

    @Override
    public VertexConsumer color(int red, int green, int blue, int alpha) {
        packedColor = (red & 0xFF) | ((green & 0xFF) << 8) | ((blue & 0xFF) << 16) | ((alpha & 0xFF) << 24);
        return this;
    }

    @Override
    public VertexConsumer color(int argb) {
        int a = (argb >> 24) & 0xFF;
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        packedColor = r | (g << 8) | (b << 16) | (a << 24);
        return this;
    }

    @Override
    public VertexConsumer texture(float u, float v) {
        texU = u;
        texV = v;
        return this;
    }

    @Override
    public VertexConsumer overlay(int u, int v) {
        return this;
    }

    @Override
    public VertexConsumer light(int u, int v) {
        return this;
    }

    @Override
    public VertexConsumer normal(float x, float y, float z) {
        return this;
    }

    @Override
    public VertexConsumer lineWidth(float width) {
        return this;
    }

    
    private void emitVertex() {
        MinecraftClient client = MinecraftClient.getInstance();
        int screenWidth = client != null && client.getWindow() != null ? client.getWindow().getScaledWidth() : 1920;
        int screenHeight = client != null && client.getWindow() != null ? client.getWindow().getScaledHeight() : 1080;
        float ndcX = (posX / screenWidth) * 2.0f - 1.0f;
        float ndcY = 1.0f - (posY / screenHeight) * 2.0f;
        float ndcZ = -0.9f + (posZ / 1000.0f) * 0.1f;
        quadVertices[quadVertexIndex][0] = ndcX;
        quadVertices[quadVertexIndex][1] = ndcY;
        quadVertices[quadVertexIndex][2] = ndcZ;
        quadVertices[quadVertexIndex][3] = texU;
        quadVertices[quadVertexIndex][4] = texV;
        quadVertices[quadVertexIndex][5] = Float.intBitsToFloat(packedColor);
        int packedNormal = 512 | (512 << 10) | (1023 << 20);
        quadVertices[quadVertexIndex][6] = Float.intBitsToFloat(packedNormal);
        quadVertices[quadVertexIndex][7] = 0.0f;

        quadVertexIndex++;
        if (quadVertexIndex >= 4) {
            emitQuadAsTriangles();
            quadVertexIndex = 0;
        }
        packedColor = 0xFFFFFFFF;
        hasVertex = false;
    }

    
    private void emitQuadAsTriangles() {
        if (vertexCount >= MAX_VERTICES - 6) {
            flushMesh();
        }
        int needed = 6 * VERTEX_STRIDE;
        if (vertexBuffer.remaining() < needed) {
            ByteBuffer newBuffer = ByteBuffer.allocateDirect(vertexBuffer.capacity() * 2)
                    .order(ByteOrder.nativeOrder());
            vertexBuffer.flip();
            newBuffer.put(vertexBuffer);
            vertexBuffer = newBuffer;
        }
        writeVertex(0);
        writeVertex(1);
        writeVertex(2);
        writeVertex(0);
        writeVertex(2);
        writeVertex(3);

        vertexCount += 6;
    }

    private void writeVertex(int quadIndex) {
        float[] v = quadVertices[quadIndex];
        vertexBuffer.putFloat(v[0]); 
        vertexBuffer.putFloat(v[1]); 
        vertexBuffer.putFloat(v[2]); 
        vertexBuffer.putFloat(v[3]); 
        vertexBuffer.putFloat(v[4]); 
        vertexBuffer.putInt(Float.floatToIntBits(v[5])); 
        vertexBuffer.putInt(Float.floatToIntBits(v[6])); 
        vertexBuffer.putInt(0); 
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
    }
}
