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

    /** Vertex buffer */
    private ByteBuffer vertexBuffer;
    private int vertexCount = 0;

    /** Pending meshes */
    private final List<CapturedMesh> pendingMeshes = new ArrayList<>();

    /** Current texture */
    private String currentTexture = null;

    /** Current vertex data */
    private float posX, posY, posZ;
    private float texU, texV;
    private int packedColor = 0xFFFFFFFF;
    private boolean hasVertex = false;

    /** Quad buffer for quad-to-triangle conversion */
    private final float[][] quadVertices = new float[4][8]; // 4 vertices, 8 floats each
    private int quadVertexIndex = 0;

    private MetalGuiVertexConsumer() {
        vertexBuffer = ByteBuffer.allocateDirect(MAX_VERTICES * VERTEX_STRIDE)
                .order(ByteOrder.nativeOrder());
    }

    public static MetalGuiVertexConsumer getInstance() {
        return INSTANCE;
    }

    /**
     * Set the current texture for subsequent vertices.
     */
    public void setTexture(String texture) {
        if (currentTexture != null && !currentTexture.equals(texture) && vertexCount > 0) {
            flushMesh();
        }
        currentTexture = texture;
    }

    /**
     * Get the current texture.
     */
    public String getCurrentTexture() {
        return currentTexture;
    }

    /**
     * Clear all state for new frame.
     */
    public void beginFrame() {
        vertexBuffer.clear();
        vertexCount = 0;
        pendingMeshes.clear();
        currentTexture = null;
        quadVertexIndex = 0;
        hasVertex = false;
    }

    /**
     * Get all captured meshes and clear.
     */
    public List<CapturedMesh> getCapturedMeshes() {
        flushMesh();
        List<CapturedMesh> result = new ArrayList<>(pendingMeshes);
        pendingMeshes.clear();
        return result;
    }

    /**
     * Flush current vertices to a mesh.
     */
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

    // ========== VertexConsumer implementation ==========

    private static int debugCount = 0;

    @Override
    public VertexConsumer vertex(float x, float y, float z) {
        // Emit previous vertex if pending
        if (hasVertex) {
            emitVertex();
        }

        // Debug: log first few vertex positions
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
        // Pack as RGBA for Metal shader (R in low bits)
        packedColor = (red & 0xFF) | ((green & 0xFF) << 8) | ((blue & 0xFF) << 16) | ((alpha & 0xFF) << 24);
        return this;
    }

    @Override
    public VertexConsumer color(int argb) {
        // Minecraft passes ARGB format - convert to RGBA
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
        // Ignored for GUI text
        return this;
    }

    @Override
    public VertexConsumer light(int u, int v) {
        // Ignored for GUI text - always fully lit
        return this;
    }

    @Override
    public VertexConsumer normal(float x, float y, float z) {
        // Ignored for GUI text
        return this;
    }

    @Override
    public VertexConsumer lineWidth(float width) {
        // Line width not used for text
        return this;
    }

    /**
     * Emit the current vertex to the quad buffer, and convert to triangles when
     * full.
     */
    private void emitVertex() {
        // Get screen dimensions for NDC conversion
        MinecraftClient client = MinecraftClient.getInstance();
        int screenWidth = client != null && client.getWindow() != null ? client.getWindow().getScaledWidth() : 1920;
        int screenHeight = client != null && client.getWindow() != null ? client.getWindow().getScaledHeight() : 1080;

        // Convert screen coordinates to NDC
        float ndcX = (posX / screenWidth) * 2.0f - 1.0f;
        float ndcY = 1.0f - (posY / screenHeight) * 2.0f;
        float ndcZ = -0.9f + (posZ / 1000.0f) * 0.1f;

        // Store vertex in quad buffer
        quadVertices[quadVertexIndex][0] = ndcX;
        quadVertices[quadVertexIndex][1] = ndcY;
        quadVertices[quadVertexIndex][2] = ndcZ;
        quadVertices[quadVertexIndex][3] = texU;
        quadVertices[quadVertexIndex][4] = texV;
        quadVertices[quadVertexIndex][5] = Float.intBitsToFloat(packedColor);
        // Normal pointing out of screen
        int packedNormal = 512 | (512 << 10) | (1023 << 20);
        quadVertices[quadVertexIndex][6] = Float.intBitsToFloat(packedNormal);
        quadVertices[quadVertexIndex][7] = 0.0f;

        quadVertexIndex++;

        // When we have 4 vertices, emit as 2 triangles
        if (quadVertexIndex >= 4) {
            emitQuadAsTriangles();
            quadVertexIndex = 0;
        }

        // Reset for next vertex
        packedColor = 0xFFFFFFFF;
        hasVertex = false;
    }

    /**
     * Convert quad to 2 triangles.
     */
    private void emitQuadAsTriangles() {
        if (vertexCount >= MAX_VERTICES - 6) {
            flushMesh();
        }

        // Ensure buffer has space
        int needed = 6 * VERTEX_STRIDE;
        if (vertexBuffer.remaining() < needed) {
            ByteBuffer newBuffer = ByteBuffer.allocateDirect(vertexBuffer.capacity() * 2)
                    .order(ByteOrder.nativeOrder());
            vertexBuffer.flip();
            newBuffer.put(vertexBuffer);
            vertexBuffer = newBuffer;
        }

        // Triangle 1: v0, v1, v2
        writeVertex(0);
        writeVertex(1);
        writeVertex(2);

        // Triangle 2: v0, v2, v3
        writeVertex(0);
        writeVertex(2);
        writeVertex(3);

        vertexCount += 6;
    }

    private void writeVertex(int quadIndex) {
        float[] v = quadVertices[quadIndex];
        vertexBuffer.putFloat(v[0]); // x
        vertexBuffer.putFloat(v[1]); // y
        vertexBuffer.putFloat(v[2]); // z
        vertexBuffer.putFloat(v[3]); // u
        vertexBuffer.putFloat(v[4]); // v
        vertexBuffer.putInt(Float.floatToIntBits(v[5])); // color
        vertexBuffer.putInt(Float.floatToIntBits(v[6])); // normal
        vertexBuffer.putInt(0); // padding
    }

    /**
     * Captured mesh data.
     */
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
