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

public class MetalGuiRenderer {

    private static final MetalGuiRenderer INSTANCE = new MetalGuiRenderer();

    
    public static final int GUI_VERTEX_STRIDE = 32;

    
    private static final int MAX_VERTICES = 65536;

    
    private static final int INITIAL_CAPACITY = 8192;

    
    private long deviceHandle = 0;

    
    private volatile boolean enabled = false;

    
    private ByteBuffer vertexBuffer;
    private int vertexCount = 0;

    
    private final List<GuiMesh> pendingMeshes = new ArrayList<>();

    
    private String currentTexture = null;

    
    private int screenWidth = 0;
    private int screenHeight = 0;

    
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

    
    public void initialize(long device) {
        this.deviceHandle = device;
        this.enabled = true;
        MetalLogger.info("[MetalGuiRenderer] Initialized with device: {}", device);
    }

    public boolean isEnabled() {
        return enabled && deviceHandle != 0;
    }

    
    private boolean frameStarted = false;

    
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
        frameQuadCount = 0; 
        logNextFrame = (frameCount % 60 == 1); 
        int prevVertexCount = vertexCount;
        int prevMeshCount = pendingMeshes.size();
        vertexBuffer.clear();
        vertexCount = 0;
        pendingMeshes.clear();
        currentTexture = null;
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
    private static int frameQuadCount = 0; 
    private static boolean logNextFrame = true; 

    public void addQuad(float x1, float y1, float x2, float y2,
            float u1, float v1, float u2, float v2,
            int color) {
        if (!enabled)
            return;
        ensureFrameStarted();

        if (screenWidth <= 0 || screenHeight <= 0) {
            System.out.println("[Metal] addQuad SKIPPED - no screen dimensions: " + screenWidth + "x" + screenHeight);
            return;
        }

        quadCount++;
        frameQuadCount++;
        if (logNextFrame && screenWidth < 600 && y1 > 100 && y1 < 200) {
            System.out.println("[Metal] addQuad IN-GAME: (" + x1 + "," + y1 + ")-(" + x2 + "," + y2 +
                    ") frameStarted=" + frameStarted + " screen=" + screenWidth + "x" + screenHeight +
                    " color=0x" + Integer.toHexString(color) + " frameQuads=" + frameQuadCount);
        }
        float ndcX1 = (x1 / screenWidth) * 2.0f - 1.0f;
        float ndcY1 = 1.0f - (y1 / screenHeight) * 2.0f; 
        float ndcX2 = (x2 / screenWidth) * 2.0f - 1.0f;
        float ndcY2 = 1.0f - (y2 / screenHeight) * 2.0f; 
        int alpha = (color >> 24) & 0xFF;
        if (frameCount <= 10 || (alpha < 255 && frameCount % 30 == 0)) {
            System.out.println("[MetalGuiRenderer] NDC CONV: gui(" + x1 + "," + y1 + ")->(" + x2 + "," + y2 +
                    ") screen=" + screenWidth + "x" + screenHeight +
                    " -> ndc(" + ndcX1 + "," + ndcY1 + ")->(" + ndcX2 + "," + ndcY2 +
                    ") color=0x" + Integer.toHexString(color));
        }
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
        boolean wasFrameStarted = frameStarted;
        ensureFrameStarted();

        if (screenWidth <= 0 || screenHeight <= 0) {
            return;
        }

        quadCount++;
        frameQuadCount++; 
        float ndcX1 = (x1 / screenWidth) * 2.0f - 1.0f;
        float ndcY1 = 1.0f - (y1 / screenHeight) * 2.0f; 
        float ndcX2 = (x2 / screenWidth) * 2.0f - 1.0f;
        float ndcY2 = 1.0f - (y2 / screenHeight) * 2.0f; 
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
        float clampedZ = Math.min(z, 1000.0f); 
        float ndcZ = -0.9f + (clampedZ / 1000.0f) * 0.4f;

        addVertex(ndcX1, ndcY1, ndcZ, u1, v1, color);
        addVertex(ndcX1, ndcY2, ndcZ, u1, v2, color);
        addVertex(ndcX2, ndcY2, ndcZ, u2, v2, color);

        addVertex(ndcX1, ndcY1, ndcZ, u1, v1, color);
        addVertex(ndcX2, ndcY2, ndcZ, u2, v2, color);
        addVertex(ndcX2, ndcY1, ndcZ, u2, v1, color);
    }

    
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
        if (currentTexture != null && vertexCount > 0) {
            flushMesh();
        }
        setTexture(null);
        addQuad(x1, y1, x2, y2, z, 0.0f, 0.0f, 0.0f, 0.0f, color);
        flushMesh();
    }

    
    private int argbToRgba(int argb) {
        int a = (argb >> 24) & 0xFF;
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return (a << 24) | (b << 16) | (g << 8) | r;
    }

    private void addVertex(float x, float y, float z, float u, float v, int color) {
        if (vertexCount >= MAX_VERTICES - 1) {
            flushMesh();
        }

        ensureCapacity();
        vertexBuffer.putFloat(x);
        vertexBuffer.putFloat(y);
        vertexBuffer.putFloat(z);
        vertexBuffer.putFloat(u);
        vertexBuffer.putFloat(v);
        vertexBuffer.putInt(argbToRgba(color));
        int packedNormal = 512 | (512 << 10) | (1023 << 20);
        vertexBuffer.putInt(packedNormal);
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
        List<MetalGuiVertexConsumer.CapturedMesh> textMeshes = MetalGuiVertexConsumer.getInstance().getCapturedMeshes();

        endFrameCount++;
        if (logNextFrame && screenWidth < 600 && frameQuadCount > 0) {
            System.out.println("[Metal] endFrame IN-GAME #" + endFrameCount + ": " + pendingMeshes.size() +
                    " meshes, " + frameQuadCount + " quads this frame, totalVerts=" + totalGuiVertices);
            logNextFrame = false;
        }

        if (pendingMeshes.isEmpty() && textMeshes.isEmpty())
            return;

        float[] orthoProj = new float[16];
        Matrix4f ortho = new Matrix4f().setOrtho(-1, 1, -1, 1, -1000, 1000);
        ortho.get(orthoProj);

        float[] cameraPos = { 0, 0, 0, 1 };
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
            System.out.println("[MetalGuiRenderer] CALLING nBeginGuiPass with deviceHandle=" + deviceHandle);
            NativeBridge.nBeginGuiPass(deviceHandle, orthoProj, cameraPos);
            System.out.println("[MetalGuiRenderer] nBeginGuiPass returned successfully");
            int itemAtlasCount = 0;
            int nullTextureMeshCount = 0;
            for (GuiMesh mesh : pendingMeshes) {
                if (mesh.vertexCount <= 0)
                    continue;
                long textureHandle = 0;
                if (mesh.textureId != null) {
                    textureHandle = EntityTextureCache.getInstance().getOrCreateTexture(mesh.textureId);
                    if (mesh.textureId.contains("items")) {
                        itemAtlasCount++;
                        if (itemAtlasCount <= 5) {
                            System.out.println("[MetalGuiRenderer] Rendering items mesh: tex=" + mesh.textureId +
                                    " handle=" + textureHandle + " verts=" + mesh.vertexCount);
                        }
                    }
                } else {
                    textureHandle = EntityTextureCache.getInstance().getWhiteTextureHandle();
                    nullTextureMeshCount++;
                    if (nullTextureMeshCount <= 3) {
                        System.out.println("[MetalGuiRenderer] Rendering NULL texture mesh: verts=" + mesh.vertexCount +
                                " (using white fallback texture handle=" + textureHandle + ")");
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
            for (MetalGuiVertexConsumer.CapturedMesh mesh : textMeshes) {
                if (mesh.vertexCount <= 0)
                    continue;
                long textureHandle = 0;
                if (mesh.textureId != null) {
                    textureHandle = EntityTextureCache.getInstance().getOrCreateTexture(mesh.textureId);
                }
                NativeBridge.nDrawEntity(deviceHandle, mesh.vertexData, mesh.vertexCount, textureHandle);
            }
            NativeBridge.nEndGuiPass(deviceHandle);

        } catch (UnsatisfiedLinkError e) {
            System.err.println("[MetalGuiRenderer] ERROR: Native link error in endFrame: " + e.getMessage());
            e.printStackTrace();
        } catch (Exception e) {
            System.err.println("[MetalGuiRenderer] ERROR: Exception in endFrame: " + e.getMessage());
            e.printStackTrace();
        }

        pendingMeshes.clear();
        frameStarted = false; 
    }

    
    public MetalGuiVertexConsumer getTextVertexConsumer() {
        return MetalGuiVertexConsumer.getInstance();
    }

    
    public boolean isFrameStarted() {
        return frameStarted;
    }

    
    public int getScreenWidth() {
        return screenWidth;
    }

    
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
