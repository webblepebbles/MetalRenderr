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

    /** Initial capacity for vertex buffer (in vertices) */
    private static final int INITIAL_CAPACITY = 4096;

    /** Maximum vertices per draw batch */
    private static final int MAX_VERTICES = 65536;

    // Current vertex being built
    private float posX, posY, posZ;
    private float u, v;
    private int color = 0xFFFFFFFF; // RGBA packed
    private int packedNormal = 0x007F7F7F; // Default normal pointing up
    private boolean hasVertex = false; // Whether we have a pending vertex to emit

    // Transform matrices (set by entity renderer) - not used since vertices are
    // pre-transformed
    private final Matrix4f positionMatrix = new Matrix4f();
    private final Matrix3f normalMatrix = new Matrix3f();

    // Quad buffer for quad-to-triangle conversion
    // Each quad (4 vertices) becomes 2 triangles (6 vertices)
    private final float[][] quadVertices = new float[4][8]; // 4 vertices, 8 floats each (pos3, uv2, color1, normal1,
                                                            // pad1)
    private int quadVertexIndex = 0; // Current vertex within quad (0-3)

    // LINE MODE: For block outlines - converts line pairs to thin quads
    private boolean lineMode = false;
    private final float[][] lineVertices = new float[2][8]; // 2 vertices per line
    private int lineVertexIndex = 0;
    private static final float LINE_WIDTH = 0.005f; // Width of converted line quads

    // Output buffer
    private ByteBuffer buffer;
    private int vertexCount = 0;

    // Batched meshes ready for upload
    private final List<CapturedMesh> capturedMeshes = new ArrayList<>();

    // Current texture identifier (set externally before render)
    private String currentTexture = null;

    // Debug
    private static int totalVerticesCaptured = 0;

    public MetalVertexConsumer() {
        allocateBuffer(INITIAL_CAPACITY);
    }

    private void allocateBuffer(int vertexCapacity) {
        int bytes = vertexCapacity * VERTEX_STRIDE;
        buffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
        buffer.clear();
    }

    /**
     * Set line mode for rendering lines as thin quads (used for block outlines).
     */
    public void setLineMode(boolean enabled) {
        this.lineMode = enabled;
        this.lineVertexIndex = 0;
    }

    /**
     * Get current count of captured meshes (for debugging).
     */
    public int getCapturedMeshCount() {
        return capturedMeshes.size();
    }

    private static int setTransformsCount = 0;

    /**
     * Set transform matrices for the current entity.
     * Called before rendering each entity.
     */
    public void setTransforms(Matrix4f position, Matrix3f normal) {
        setTransformsCount++;
        this.positionMatrix.set(position);
        this.normalMatrix.set(normal);

        // Debug: Log the position matrix translation component
        if (setTransformsCount <= 10) {
            // Matrix4f stores column-major, translation is in m30, m31, m32 (indices
            // 12,13,14)
            System.out.println("[MetalVertexConsumer] setTransforms #" + setTransformsCount +
                    " posMatrix translation: (" + position.m30() + ", " + position.m31() + ", " + position.m32() + ")");
        }
    }

    /**
     * Set the texture identifier for subsequent vertices.
     */
    private static int setTextureCount = 0;

    public void setTexture(String texture) {
        setTextureCount++;
        if (setTextureCount <= 50) {
            System.out.println("[MetalVertexConsumer] setTexture #" + setTextureCount + ": " + texture +
                    " (current was " + currentTexture + ", vertexCount=" + vertexCount + ")");
        }

        // DEBUG: If setting null while we have vertices, print stack trace
        if (texture == null && vertexCount > 0 && currentTexture != null) {
            System.out.println(
                    "[MetalVertexConsumer] WARNING: Setting texture to null with " + vertexCount + " vertices! Stack:");
            Thread.dumpStack();
        }

        // If texture changes, flush current mesh
        if (currentTexture != null && !currentTexture.equals(texture) && vertexCount > 0) {
            flushMesh();
        }
        this.currentTexture = texture;
    }

    /**
     * Get the current texture identifier.
     */
    public String getCurrentTexture() {
        return this.currentTexture;
    }

    /**
     * Flush current vertices to a CapturedMesh and reset for next batch.
     */
    public void flushMesh() {
        // First emit any pending vertex
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

        // Reset
        buffer.clear();
        vertexCount = 0;
        quadVertexIndex = 0; // Reset quad buffer on flush
    }

    private static int harvestCount = 0;

    public List<CapturedMesh> harvest() {
        harvestCount++;
        if (harvestCount <= 50 || harvestCount % 100 == 0) {
            System.out.println("[MetalVertexConsumer] harvest #" + harvestCount +
                    ": vertexCount=" + vertexCount + ", capturedMeshes.size=" + capturedMeshes.size());
        }

        flushMesh();
        List<CapturedMesh> result = new ArrayList<>(capturedMeshes);
        capturedMeshes.clear();
        // CRITICAL: Clear texture after harvest so next capture cycle starts fresh
        // This prevents entity textures from bleeding into hand rendering
        currentTexture = null;
        return result;
    }

    private static int beginFrameCount = 0;

    public void beginFrame() {
        beginFrameCount++;
        debugVertexCount = 0; // Reset debug counter for new frame
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
        quadVertexIndex = 0; // Reset quad buffer
        lineMode = false; // Reset line mode
        lineVertexIndex = 0; // Reset line buffer
    }

    private static int vertexCallCount = 0;

    @Override
    public VertexConsumer vertex(float x, float y, float z) {
        vertexCallCount++;

        if (hasVertex) {
            emitVertex();
        }

        this.posX = x;
        this.posY = y;
        this.posZ = z;
        this.hasVertex = true;

        return this;
    }

    /**
     * addVertex is used in some Minecraft versions/mods.
     * Not an override, but provide it as a fallback.
     */
    public VertexConsumer addVertex(float x, float y, float z) {
        return vertex(x, y, z);
    }

    @Override
    public VertexConsumer color(int red, int green, int blue, int alpha) {
        // Pack as RGBA (R in low bits, A in high bits) - matches shader decoding
        this.color = (alpha << 24) | (blue << 16) | (green << 8) | red;
        return this;
    }

    @Override
    public VertexConsumer color(int argb) {
        // Minecraft passes ARGB format - convert to RGBA
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
        // Packed overlay coords (unused for basic entity pass)
        // Just ignore for now
        return this;
    }

    @Override
    public VertexConsumer light(int u, int v) {
        // Packed lightmap coords - could be used for lighting
        // For now, bake into color
        return this;
    }

    @Override
    public VertexConsumer normal(float x, float y, float z) {
        // Transform normal by normal matrix
        Vector3f n = new Vector3f(x, y, z);
        n.mul(normalMatrix);
        n.normalize();

        // Pack to 10-10-10-2 format
        int nx = (int) ((n.x * 0.5f + 0.5f) * 1023.0f) & 0x3FF;
        int ny = (int) ((n.y * 0.5f + 0.5f) * 1023.0f) & 0x3FF;
        int nz = (int) ((n.z * 0.5f + 0.5f) * 1023.0f) & 0x3FF;
        this.packedNormal = nx | (ny << 10) | (nz << 20);
        return this;
    }

    @Override
    public VertexConsumer lineWidth(float width) {
        // Line width is not used for entity rendering, just ignore
        return this;
    }

    /**
     * Internal method to emit the current vertex to the buffer.
     * Minecraft entity models use quads (4 vertices), but Metal draws triangles.
     * We buffer 4 vertices, then emit 2 triangles (6 vertices).
     * 
     * In LINE MODE: Lines use 2 vertices each, converted to thin quads.
     */
    private static int debugVertexCount = 0;

    private void emitVertex() {
        // NOTE: Minecraft's ModelPart.render() already transforms vertices using the
        // MatrixStack
        // before calling VertexConsumer.vertex(). So the positions we receive are
        // already
        // in camera-relative space. We should NOT apply positionMatrix again!
        float transformedX = posX;
        float transformedY = posY;
        float transformedZ = posZ;

        // Debug: Log first few positions per frame
        if (debugVertexCount < 5) {
            System.out.println("[MetalVertexConsumer] emitVertex #" + debugVertexCount +
                    " pos=(" + posX + "," + posY + "," + posZ + ")" +
                    " uv=(" + u + "," + v + ") color=0x" + Integer.toHexString(color) +
                    " tex=" + currentTexture);
            debugVertexCount++;
        }

        if (lineMode) {
            // LINE MODE: Buffer 2 vertices, then emit a thin quad
            lineVertices[lineVertexIndex][0] = transformedX;
            lineVertices[lineVertexIndex][1] = transformedY;
            lineVertices[lineVertexIndex][2] = transformedZ;
            lineVertices[lineVertexIndex][3] = u;
            lineVertices[lineVertexIndex][4] = v;
            lineVertices[lineVertexIndex][5] = Float.intBitsToFloat(color);
            lineVertices[lineVertexIndex][6] = Float.intBitsToFloat(packedNormal);
            lineVertices[lineVertexIndex][7] = 0.0f;

            lineVertexIndex++;

            if (lineVertexIndex >= 2) {
                emitLineAsQuad();
                lineVertexIndex = 0;
            }

            resetVertex();
            return;
        }

        // QUAD MODE: Store current vertex in quad buffer
        // Format: pos3, uv2, color, normal, pad (as floats/ints packed)
        // Use transformed position (already computed above)
        quadVertices[quadVertexIndex][0] = transformedX;
        quadVertices[quadVertexIndex][1] = transformedY;
        quadVertices[quadVertexIndex][2] = transformedZ;
        quadVertices[quadVertexIndex][3] = u;
        quadVertices[quadVertexIndex][4] = v;
        quadVertices[quadVertexIndex][5] = Float.intBitsToFloat(color);
        quadVertices[quadVertexIndex][6] = Float.intBitsToFloat(packedNormal);
        quadVertices[quadVertexIndex][7] = 0.0f; // padding

        quadVertexIndex++;

        // When we have 4 vertices (a complete quad), emit 2 triangles
        if (quadVertexIndex >= 4) {
            emitQuadAsTriangles();
            quadVertexIndex = 0;
        }

        // Reset attributes for next vertex
        resetVertex();
    }

    /**
     * Convert a line (2 vertices) to a thin quad (6 vertices) for rendering.
     * Creates a billboard-style quad perpendicular to the view direction.
     */
    private void emitLineAsQuad() {
        // Check capacity for 6 vertices
        if (vertexCount >= MAX_VERTICES - 6) {
            flushMesh();
        }

        // Ensure buffer has space
        int neededBytes = 6 * VERTEX_STRIDE;
        if (buffer.remaining() < neededBytes) {
            ByteBuffer newBuffer = ByteBuffer.allocateDirect(buffer.capacity() * 2)
                    .order(ByteOrder.nativeOrder());
            buffer.flip();
            newBuffer.put(buffer);
            buffer = newBuffer;
        }

        // Get line endpoints
        float x1 = lineVertices[0][0], y1 = lineVertices[0][1], z1 = lineVertices[0][2];
        float x2 = lineVertices[1][0], y2 = lineVertices[1][1], z2 = lineVertices[1][2];

        // Calculate line direction
        float dx = x2 - x1;
        float dy = y2 - y1;
        float dz = z2 - z1;
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 0.0001f)
            return; // Skip degenerate lines

        // Normalize direction
        dx /= len;
        dy /= len;
        dz /= len;

        // Find a perpendicular vector (cross with up, or right if parallel to up)
        float px, py, pz;
        if (Math.abs(dy) < 0.99f) {
            // Cross with up vector (0, 1, 0)
            px = dz;
            py = 0;
            pz = -dx;
        } else {
            // Cross with right vector (1, 0, 0)
            px = 0;
            py = -dz;
            pz = dy;
        }

        // Normalize perpendicular
        float plen = (float) Math.sqrt(px * px + py * py + pz * pz);
        if (plen > 0.0001f) {
            px /= plen;
            py /= plen;
            pz /= plen;
        }

        // Scale by line width
        px *= LINE_WIDTH;
        py *= LINE_WIDTH;
        pz *= LINE_WIDTH;

        // Create 4 corners of the line quad
        float[] v0 = { x1 - px, y1 - py, z1 - pz, lineVertices[0][3], lineVertices[0][4],
                lineVertices[0][5], lineVertices[0][6], 0 };
        float[] v1 = { x1 + px, y1 + py, z1 + pz, lineVertices[0][3], lineVertices[0][4],
                lineVertices[0][5], lineVertices[0][6], 0 };
        float[] v2 = { x2 + px, y2 + py, z2 + pz, lineVertices[1][3], lineVertices[1][4],
                lineVertices[1][5], lineVertices[1][6], 0 };
        float[] v3 = { x2 - px, y2 - py, z2 - pz, lineVertices[1][3], lineVertices[1][4],
                lineVertices[1][5], lineVertices[1][6], 0 };

        // Emit as 2 triangles
        writeVertexToBuffer(v0);
        writeVertexToBuffer(v1);
        writeVertexToBuffer(v2);

        writeVertexToBuffer(v0);
        writeVertexToBuffer(v2);
        writeVertexToBuffer(v3);

        vertexCount += 6;
    }

    /**
     * Convert a quad (4 vertices) to 2 triangles (6 vertices) and emit to buffer.
     * Quad vertices: v0, v1, v2, v3 (in order from Minecraft)
     * Triangle 1: v0, v1, v2
     * Triangle 2: v0, v2, v3
     */
    private void emitQuadAsTriangles() {
        // Check capacity for 6 vertices
        if (vertexCount >= MAX_VERTICES - 6) {
            flushMesh();
        }

        // Ensure buffer has space for 6 vertices
        int neededBytes = 6 * VERTEX_STRIDE;
        if (buffer.remaining() < neededBytes) {
            ByteBuffer newBuffer = ByteBuffer.allocateDirect(buffer.capacity() * 2)
                    .order(ByteOrder.nativeOrder());
            buffer.flip();
            newBuffer.put(buffer);
            buffer = newBuffer;
        }

        // Triangle 1: v0, v1, v2
        writeVertexToBuffer(quadVertices[0]);
        writeVertexToBuffer(quadVertices[1]);
        writeVertexToBuffer(quadVertices[2]);

        // Triangle 2: v0, v2, v3
        writeVertexToBuffer(quadVertices[0]);
        writeVertexToBuffer(quadVertices[2]);
        writeVertexToBuffer(quadVertices[3]);

        vertexCount += 6;
    }

    // Debug counter for quad emissions
    private static int quadDebugCount = 0;

    /**
     * Write a single vertex to the buffer from the packed format.
     */
    private void writeVertexToBuffer(float[] v) {
        // Position: float3 (12 bytes)
        buffer.putFloat(v[0]);
        buffer.putFloat(v[1]);
        buffer.putFloat(v[2]);

        // UV: float2 (8 bytes)
        buffer.putFloat(v[3]);
        buffer.putFloat(v[4]);

        // Color: RGBA8 (4 bytes) - stored as float bits
        // MUST use floatToRawIntBits to preserve NaN bit patterns!
        // floatToIntBits() canonicalizes NaN to 0x7FC00000 which breaks packed colors
        buffer.putInt(Float.floatToRawIntBits(v[5]));

        // Normal: packed 10-10-10-2 (4 bytes) - stored as float bits
        buffer.putInt(Float.floatToRawIntBits(v[6]));

        // Padding (4 bytes)
        buffer.putInt(0);
    }

    private void resetVertex() {
        posX = posY = posZ = 0;
        u = v = 0;
        color = 0xFFFFFFFF;
        packedNormal = 0x007F7F7F;
        hasVertex = false;
    }

    // ========================================================================
    // Debug/Stats
    // ========================================================================

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

    // ========================================================================
    // Inner class for captured mesh data
    // ========================================================================

    /**
     * Represents a captured batch of entity vertices ready for Metal upload.
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

        public int getByteSize() {
            return vertexCount * VERTEX_STRIDE;
        }
    }
}
