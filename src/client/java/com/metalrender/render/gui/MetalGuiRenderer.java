package com.metalrender.render.gui;

import com.metalrender.entity.EntityTextureCache;
import com.metalrender.nativebridge.NativeBridge;
import com.metalrender.render.unified.MetalRenderBatch;
import com.metalrender.render.unified.MetalRenderType;
import com.metalrender.util.MetalLogger;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Metal-accelerated GUI renderer.
 * 
 * Captures GUI draw calls and renders them using Metal for improved
 * performance.
 * This handles:
 * - HUD elements (hotbar, health, hunger, etc.)
 * - Inventory screens
 * - Menu screens
 * - Chat and text
 * - Item rendering in slots
 * 
 * NOTE: Uses entity shader vertex format (32 bytes) for compatibility:
 * - position (12 bytes) + uv (8 bytes) + color (4 bytes) + normal (4 bytes) +
 * padding (4 bytes)
 */
public class MetalGuiRenderer {

    private static final MetalGuiRenderer INSTANCE = new MetalGuiRenderer();

    /**
     * GUI vertex stride: MUST MATCH entity format = 32 bytes
     * pos (12) + uv (8) + color (4) + normal (4) + padding (4)
     */
    public static final int GUI_VERTEX_STRIDE = 32;

    /** Maximum vertices per batch */
    private static final int MAX_VERTICES = 65536;

    /** Initial capacity */
    private static final int INITIAL_CAPACITY = 8192;

    /** Native device handle */
    private long deviceHandle = 0;

    /** Whether GUI rendering is enabled */
    private volatile boolean enabled = false;

    /** Current vertex buffer */
    private ByteBuffer vertexBuffer;
    private int vertexCount = 0;

    /** Batched GUI meshes */
    private final List<GuiMesh> pendingMeshes = new ArrayList<>();

    /** Current texture */
    private String currentTexture = null;

    /** Screen dimensions */
    private int screenWidth = 0;
    private int screenHeight = 0;

    /** Debug counters */
    private int frameCount = 0;
    private int totalGuiVertices = 0;

    private MetalGuiRenderer() {
        allocateBuffer(INITIAL_CAPACITY);
    }

    public static MetalGuiRenderer getInstance() {
        return INSTANCE;
    }

    private void allocateBuffer(int capacity) {
        vertexBuffer = ByteBuffer.allocateDirect(capacity * GUI_VERTEX_STRIDE)
                .order(ByteOrder.nativeOrder());
    }

    /**
     * Initialize with Metal device handle.
     */
    public void initialize(long device) {
        this.deviceHandle = device;
        this.enabled = true;
        MetalLogger.info("[MetalGuiRenderer] Initialized with device: {}", device);
    }

    public boolean isEnabled() {
        return enabled && deviceHandle != 0;
    }

    /** Track if frame has been started this render cycle */
    private boolean frameStarted = false;

    /**
     * Ensure a frame has been started. Auto-starts if needed.
     * Called automatically by addQuad when no frame is active.
     * Also publicly callable for text capture setup.
     */
    public void ensureFrameStarted() {
        if (frameStarted)
            return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getWindow() == null)
            return;

        int width = client.getWindow().getScaledWidth();
        int height = client.getWindow().getScaledHeight();

        if (width <= 0 || height <= 0)
            return;

        beginFrame(width, height);
    }

    public void beginFrame(int width, int height) {
        if (!enabled)
            return;

        frameCount++;
        screenWidth = width;
        screenHeight = height;
        totalGuiVertices = 0;
        frameStarted = true;
        frameQuadCount = 0; // Reset per-frame quad counter
        logNextFrame = (frameCount % 60 == 1); // Log every ~1 second

        // Reset state
        int prevVertexCount = vertexCount;
        int prevMeshCount = pendingMeshes.size();
        vertexBuffer.clear();
        vertexCount = 0;
        pendingMeshes.clear();
        currentTexture = null;

        // Log frame reset if we had content
        if (prevVertexCount > 0 || prevMeshCount > 0) {
            if (frameCount <= 30 || frameCount % 60 == 0) {
                System.out.println("[MetalGuiRenderer] beginFrame #" + frameCount + " CLEARED prev: " +
                        prevVertexCount + " verts, " + prevMeshCount + " meshes (screen=" + width + "x" + height + ")");
            }
        }
    }

    public void setTexture(Identifier texture) {
        String texId = texture != null ? texture.toString() : null;
        if (currentTexture != null && !currentTexture.equals(texId) && vertexCount > 0) {
            flushMesh();
        }
        currentTexture = texId;
    }

    private static int quadCount = 0;
    private static int frameQuadCount = 0; // Quads added in current frame
    private static boolean logNextFrame = true; // Log the next frame's activity

    public void addQuad(float x1, float y1, float x2, float y2,
            float u1, float v1, float u2, float v2,
            int color) {
        if (!enabled)
            return;

        // Auto-start frame if needed
        ensureFrameStarted();

        if (screenWidth <= 0 || screenHeight <= 0) {
            // Still no valid screen dimensions - skip
            System.out.println("[Metal] addQuad SKIPPED - no screen dimensions: " + screenWidth + "x" + screenHeight);
            return;
        }

        quadCount++;
        frameQuadCount++;

        // Log quad additions for in-game menu (screen size ~570x320)
        if (logNextFrame && screenWidth < 600 && y1 > 100 && y1 < 200) {
            System.out.println("[Metal] addQuad IN-GAME: (" + x1 + "," + y1 + ")-(" + x2 + "," + y2 +
                    ") frameStarted=" + frameStarted + " screen=" + screenWidth + "x" + screenHeight +
                    " color=0x" + Integer.toHexString(color) + " frameQuads=" + frameQuadCount);
        }

        // Convert GUI coordinates to NDC
        // GUI X: [0, screenWidth] -> NDC X: [-1, 1]
        // GUI Y: [0, screenHeight] -> NDC Y: [1, -1] (Y flipped for OpenGL convention)
        float ndcX1 = (x1 / screenWidth) * 2.0f - 1.0f;
        float ndcY1 = 1.0f - (y1 / screenHeight) * 2.0f; // Y flip for OpenGL convention
        float ndcX2 = (x2 / screenWidth) * 2.0f - 1.0f;
        float ndcY2 = 1.0f - (y2 / screenHeight) * 2.0f; // Y flip for OpenGL convention

        // DEBUG: Log coordinate conversion for fill quads (alpha < 255 might be
        // highlights)
        int alpha = (color >> 24) & 0xFF;
        if (frameCount <= 10 || (alpha < 255 && frameCount % 30 == 0)) {
            System.out.println("[MetalGuiRenderer] NDC CONV: gui(" + x1 + "," + y1 + ")->(" + x2 + "," + y2 +
                    ") screen=" + screenWidth + "x" + screenHeight +
                    " -> ndc(" + ndcX1 + "," + ndcY1 + ")->(" + ndcX2 + "," + ndcY2 +
                    ") color=0x" + Integer.toHexString(color));
        }

        // GUI should always be in front - use z very close to camera (near plane)
        // In NDC after ortho: z=-1 is near, z=1 is far
        // We use z=-0.9 to be very close to near plane
        float ndcZ = -0.9f;

        addVertex(ndcX1, ndcY1, ndcZ, u1, v1, color);
        addVertex(ndcX1, ndcY2, ndcZ, u1, v2, color);
        addVertex(ndcX2, ndcY2, ndcZ, u2, v2, color);
        addVertex(ndcX1, ndcY1, ndcZ, u1, v1, color);
        addVertex(ndcX2, ndcY2, ndcZ, u2, v2, color);
        addVertex(ndcX2, ndcY1, ndcZ, u2, v1, color);
    }

    public void addQuad(float x1, float y1, float x2, float y2, float z,
            float u1, float v1, float u2, float v2,
            int color) {
        if (!enabled)
            return;

        // Log when addQuad is called without frame started - this would explain missing
        // quads
        boolean wasFrameStarted = frameStarted;

        // Auto-start frame if needed
        ensureFrameStarted();

        if (screenWidth <= 0 || screenHeight <= 0) {
            return;
        }

        quadCount++;
        frameQuadCount++; // Track quads for debugging

        // Convert GUI coordinates to NDC
        // GUI X: [0, screenWidth] -> NDC X: [-1, 1]
        // GUI Y: [0, screenHeight] -> NDC Y: [1, -1] (Y flipped for OpenGL convention)
        float ndcX1 = (x1 / screenWidth) * 2.0f - 1.0f;
        float ndcY1 = 1.0f - (y1 / screenHeight) * 2.0f; // Y flip for OpenGL convention
        float ndcX2 = (x2 / screenWidth) * 2.0f - 1.0f;
        float ndcY2 = 1.0f - (y2 / screenHeight) * 2.0f; // Y flip for OpenGL convention

        // Check if this looks like inventory item position (high z-value or in
        // inventory area)
        // Log with full coordinate info to debug positioning
        if (z > 50 || (y1 > 80 && y1 < 300 && x1 > 5 && x1 < 300)) {
            System.out.println("[MetalGuiRenderer] addQuad ITEM: pos=(" + x1 + "," + y1 + ")-(" + x2 + "," + y2 + ")" +
                    " screen=" + screenWidth + "x" + screenHeight +
                    " z=" + z +
                    " NDC=(" + String.format("%.3f", ndcX1) + "," + String.format("%.3f", ndcY1) + ")-(" +
                    String.format("%.3f", ndcX2) + "," + String.format("%.3f", ndcY2) + ")" +
                    " tex="
                    + (currentTexture != null ? currentTexture.substring(Math.max(0, currentTexture.length() - 30))
                            : "null"));
        }

        // Convert Minecraft z to NDC z
        // Minecraft GUI uses z values from 0 to 25000+
        // Clamp z to reasonable range to avoid clipping, but preserve relative ordering
        // GUI elements should be in front of everything, use range [-0.9, -0.5]
        float clampedZ = Math.min(z, 1000.0f); // Clamp high z values
        float ndcZ = -0.9f + (clampedZ / 1000.0f) * 0.4f;

        addVertex(ndcX1, ndcY1, ndcZ, u1, v1, color);
        addVertex(ndcX1, ndcY2, ndcZ, u1, v2, color);
        addVertex(ndcX2, ndcY2, ndcZ, u2, v2, color);

        addVertex(ndcX1, ndcY1, ndcZ, u1, v1, color);
        addVertex(ndcX2, ndcY2, ndcZ, u2, v2, color);
        addVertex(ndcX2, ndcY1, ndcZ, u2, v1, color);
    }

    /**
     * Add a solid filled quad (no texture, just color).
     * Uses a special "no texture" batch that the shader handles as solid color.
     */
    private static int fillQuadCount = 0;

    public void addFillQuad(float x1, float y1, float x2, float y2, float z, int color) {
        if (!enabled) {
            fillQuadCount++;
            if (fillQuadCount <= 30) {
                System.out
                        .println("[MetalGuiRenderer] addFillQuad SKIPPED (not enabled) - pos=(" + x1 + "," + y1 + ")");
            }
            return;
        }

        fillQuadCount++;
        if (fillQuadCount <= 30 || fillQuadCount % 300 == 0) {
            System.out.println("[MetalGuiRenderer] addFillQuad: pos=(" + x1 + "," + y1 + ")-(" + x2 + "," + y2 +
                    ") z=" + z + " color=0x" + Integer.toHexString(color) + " screen=" + screenWidth + "x"
                    + screenHeight);
        }

        // Flush any pending textured mesh before adding fill quad
        if (currentTexture != null && vertexCount > 0) {
            flushMesh();
        }

        // Ensure no texture is bound for solid color rendering
        setTexture(null);

        // Use UV coords of 0,0 to signal "solid color" to shader
        // The shader should use vertex color when UVs are all zero
        addQuad(x1, y1, x2, y2, z, 0.0f, 0.0f, 0.0f, 0.0f, color);

        // Immediately flush this fill quad so it gets its own mesh with null texture
        flushMesh();
    }

    /**
     * Convert Minecraft ARGB color to shader RGBA format.
     * Minecraft uses ARGB (A in high byte), shader expects ABGR/RGBA little-endian:
     * - bits 0-7: R, bits 8-15: G, bits 16-23: B, bits 24-31: A
     */
    private int argbToRgba(int argb) {
        int a = (argb >> 24) & 0xFF;
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        // Pack as RGBA (R in low bits for shader decoding)
        return (a << 24) | (b << 16) | (g << 8) | r;
    }

    private void addVertex(float x, float y, float z, float u, float v, int color) {
        if (vertexCount >= MAX_VERTICES - 1) {
            flushMesh();
        }

        ensureCapacity();
        // Position (12 bytes)
        vertexBuffer.putFloat(x);
        vertexBuffer.putFloat(y);
        vertexBuffer.putFloat(z);

        // UV (8 bytes)
        vertexBuffer.putFloat(u);
        vertexBuffer.putFloat(v);

        // Color (4 bytes) - Convert ARGB to RGBA for shader
        vertexBuffer.putInt(argbToRgba(color));

        // Normal (4 bytes) - GUI faces camera, use Z-forward normal
        // 10-10-10-2 format: nx=0.5, ny=0.5, nz=1.0 (pointing out of screen)
        // = 512 for x, 512 for y, 1023 for z (0.0->0.5->1.0 maps to 0->511->1023)
        // z = 1.0 -> (1.0 + 1.0) / 2.0 * 1023 = 1023
        // Packed: (512) | (512 << 10) | (1023 << 20) = 0x3FF00200
        int packedNormal = 512 | (512 << 10) | (1023 << 20);
        vertexBuffer.putInt(packedNormal);

        // Padding (4 bytes)
        vertexBuffer.putInt(0);

        vertexCount++;
    }

    private void ensureCapacity() {
        if (vertexBuffer.remaining() < GUI_VERTEX_STRIDE) {
            ByteBuffer newBuffer = ByteBuffer.allocateDirect(vertexBuffer.capacity() * 2)
                    .order(ByteOrder.nativeOrder());
            vertexBuffer.flip();
            newBuffer.put(vertexBuffer);
            vertexBuffer = newBuffer;
        }
    }

    public void flushMesh() {
        if (vertexCount == 0)
            return;

        vertexBuffer.flip();
        ByteBuffer copy = ByteBuffer.allocateDirect(vertexBuffer.remaining())
                .order(ByteOrder.nativeOrder());
        copy.put(vertexBuffer);
        copy.flip();

        pendingMeshes.add(new GuiMesh(copy, vertexCount, currentTexture));
        totalGuiVertices += vertexCount;

        vertexBuffer.clear();
        vertexCount = 0;
    }

    private static int endFrameCount = 0;

    public void endFrame() {
        if (!enabled || deviceHandle == 0)
            return;

        flushMesh();

        // Also collect any captured text meshes from MetalGuiVertexConsumer
        List<MetalGuiVertexConsumer.CapturedMesh> textMeshes = MetalGuiVertexConsumer.getInstance().getCapturedMeshes();

        endFrameCount++;

        // Log when we have quads from in-game menu context
        if (logNextFrame && screenWidth < 600 && frameQuadCount > 0) {
            System.out.println("[Metal] endFrame IN-GAME #" + endFrameCount + ": " + pendingMeshes.size() +
                    " meshes, " + frameQuadCount + " quads this frame, totalVerts=" + totalGuiVertices);
            logNextFrame = false;
        }

        if (pendingMeshes.isEmpty() && textMeshes.isEmpty())
            return;

        float[] orthoProj = new float[16];
        // Orthographic projection for GUI - maps NDC to NDC with Z adjustment
        Matrix4f ortho = new Matrix4f().setOrtho(-1, 1, -1, 1, -1000, 1000);
        ortho.get(orthoProj);

        float[] cameraPos = { 0, 0, 0, 1 };

        // DEBUG: Log GUI render activity
        int totalMeshes = pendingMeshes.size() + textMeshes.size();
        int totalVerts = 0;
        for (GuiMesh mesh : pendingMeshes)
            totalVerts += mesh.vertexCount;
        for (MetalGuiVertexConsumer.CapturedMesh mesh : textMeshes)
            totalVerts += mesh.vertexCount;

        if (endFrameCount <= 10 || endFrameCount % 60 == 0) {
            MetalLogger.info("[MetalGuiRenderer] endFrame #%d: %d meshes, %d total verts, screenQuads=%d",
                    endFrameCount, totalMeshes, totalVerts, frameQuadCount);
        }

        try {
            // Use GUI pass (no depth testing) instead of entity pass
            System.out.println("[MetalGuiRenderer] CALLING nBeginGuiPass with deviceHandle=" + deviceHandle);
            NativeBridge.nBeginGuiPass(deviceHandle, orthoProj, cameraPos);
            System.out.println("[MetalGuiRenderer] nBeginGuiPass returned successfully");

            // Render GUI texture quads (buttons, icons, etc.)
            int itemAtlasCount = 0;
            int nullTextureMeshCount = 0;
            for (GuiMesh mesh : pendingMeshes) {
                if (mesh.vertexCount <= 0)
                    continue;
                long textureHandle = 0;
                if (mesh.textureId != null) {
                    textureHandle = EntityTextureCache.getInstance().getOrCreateTexture(mesh.textureId);
                    // Track items atlas specifically
                    if (mesh.textureId.contains("items")) {
                        itemAtlasCount++;
                        if (itemAtlasCount <= 5) {
                            System.out.println("[MetalGuiRenderer] Rendering items mesh: tex=" + mesh.textureId +
                                    " handle=" + textureHandle + " verts=" + mesh.vertexCount);
                        }
                    }
                } else {
                    // Null texture - use white fallback texture for solid color rendering
                    textureHandle = EntityTextureCache.getInstance().getWhiteTextureHandle();
                    nullTextureMeshCount++;
                    if (nullTextureMeshCount <= 3) {
                        System.out.println("[MetalGuiRenderer] Rendering NULL texture mesh: verts=" + mesh.vertexCount +
                                " (using white fallback texture handle=" + textureHandle + ")");
                        // Debug: dump first vertex data
                        if (mesh.vertexData.remaining() >= 32) {
                            mesh.vertexData.rewind();
                            float x = mesh.vertexData.getFloat();
                            float y = mesh.vertexData.getFloat();
                            float z = mesh.vertexData.getFloat();
                            float u = mesh.vertexData.getFloat();
                            float v = mesh.vertexData.getFloat();
                            int color = mesh.vertexData.getInt();
                            mesh.vertexData.rewind();
                            System.out.println("[MetalGuiRenderer]   First vertex: pos=(" + x + "," + y + "," + z +
                                    ") uv=(" + u + "," + v + ") color=0x" + Integer.toHexString(color));
                        }
                    }
                }
                NativeBridge.nDrawEntity(deviceHandle, mesh.vertexData, mesh.vertexCount, textureHandle);
            }
            if (itemAtlasCount > 0 && (endFrameCount <= 10 || endFrameCount % 60 == 0)) {
                System.out.println("[MetalGuiRenderer] endFrame: " + itemAtlasCount + " item atlas meshes rendered");
            }

            // Render captured text meshes
            for (MetalGuiVertexConsumer.CapturedMesh mesh : textMeshes) {
                if (mesh.vertexCount <= 0)
                    continue;
                long textureHandle = 0;
                if (mesh.textureId != null) {
                    textureHandle = EntityTextureCache.getInstance().getOrCreateTexture(mesh.textureId);
                }
                NativeBridge.nDrawEntity(deviceHandle, mesh.vertexData, mesh.vertexCount, textureHandle);
            }

            // Use GUI pass end
            NativeBridge.nEndGuiPass(deviceHandle);

        } catch (UnsatisfiedLinkError e) {
            System.err.println("[MetalGuiRenderer] ERROR: Native link error in endFrame: " + e.getMessage());
            e.printStackTrace();
        } catch (Exception e) {
            System.err.println("[MetalGuiRenderer] ERROR: Exception in endFrame: " + e.getMessage());
            e.printStackTrace();
        }

        pendingMeshes.clear();
        frameStarted = false; // Reset for next frame
    }

    /**
     * Get the GUI vertex consumer for text rendering.
     */
    public MetalGuiVertexConsumer getTextVertexConsumer() {
        return MetalGuiVertexConsumer.getInstance();
    }

    /**
     * Check if frame has been started.
     */
    public boolean isFrameStarted() {
        return frameStarted;
    }

    /**
     * Get current screen width.
     */
    public int getScreenWidth() {
        return screenWidth;
    }

    /**
     * Get current screen height.
     */
    public int getScreenHeight() {
        return screenHeight;
    }

    public void destroy() {
        enabled = false;
        vertexBuffer.clear();
        pendingMeshes.clear();
        deviceHandle = 0;
    }

    public static class GuiMesh {
        public final ByteBuffer vertexData;
        public final int vertexCount;
        public final String textureId;

        public GuiMesh(ByteBuffer data, int count, String texture) {
            this.vertexData = data;
            this.vertexCount = count;
            this.textureId = texture;
        }
    }
}
