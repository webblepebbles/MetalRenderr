package com.metalrender.render.item;

import com.metalrender.entity.EntityTextureCache;
import com.metalrender.nativebridge.NativeBridge;
import com.metalrender.util.MetalLogger;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.util.Identifier;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

public class MetalItemRenderer {

    private static final MetalItemRenderer INSTANCE = new MetalItemRenderer();

    public static final int VERTEX_STRIDE = 32;
    private static final int MAX_VERTICES = 16384;
    private static final int INITIAL_CAPACITY = 4096;
    private long deviceHandle = 0;
    private volatile boolean enabled = true;
    private final ItemVertexConsumer vertexConsumer = new ItemVertexConsumer();
    private final List<ItemMesh> pendingMeshes = new ArrayList<>();

    /** Current transform matrices */
    private final Matrix4f positionMatrix = new Matrix4f();
    private final Matrix3f normalMatrix = new Matrix3f();

    /** Frame uniforms */
    private final float[] viewProjection = new float[16];
    private final float[] cameraPos = new float[4];

    /** Debug counters */
    private int frameCount = 0;
    private int itemsRenderedThisFrame = 0;
    private int verticesRenderedThisFrame = 0;

    /** Track whether current item is a 3D block or flat item */
    private boolean currentItemIs3DBlock = false;
    private int blockItemsThisFrame = 0;
    private int flatItemsThisFrame = 0;

    /**
     * Track whether items are being rendered in GUI mode (inventory) or world mode
     * (hotbar)
     */
    private boolean currentGuiMode = false;

    private MetalItemRenderer() {
    }

    public static MetalItemRenderer getInstance() {
        return INSTANCE;
    }

    /**
     * Initialize with Metal device handle.
     */
    public void initialize(long device) {
        this.deviceHandle = device;
        this.enabled = true;
        MetalLogger.info("[MetalItemRenderer] Initialized with device: {}", device);
    }

    public boolean isEnabled() {
        return enabled && deviceHandle != 0;
    }

    /**
     * Begin a new item frame.
     */
    public void beginFrame(Matrix4f viewProj, float camX, float camY, float camZ) {
        if (!enabled)
            return;

        frameCount++;
        itemsRenderedThisFrame = 0;
        verticesRenderedThisFrame = 0;
        blockItemsThisFrame = 0;
        flatItemsThisFrame = 0;

        viewProj.get(viewProjection);
        cameraPos[0] = camX;
        cameraPos[1] = camY;
        cameraPos[2] = camZ;
        cameraPos[3] = 1.0f;

        vertexConsumer.beginFrame();
        pendingMeshes.clear();
        currentItemIs3DBlock = false;
    }

    /**
     * Set whether the current item being rendered is a 3D block or flat item.
     * 3D block items have full cube geometry and need proper depth testing.
     * Flat items are essentially billboards (2 quads - front and back).
     */
    public void setIs3DBlockItem(boolean is3DBlock) {
        this.currentItemIs3DBlock = is3DBlock;
        vertexConsumer.setIs3DBlock(is3DBlock);
    }

    /**
     * Check if current item is a 3D block.
     */
    public boolean isCurrentItem3DBlock() {
        return currentItemIs3DBlock;
    }

    /**
     * Set whether items are being rendered in GUI mode (inventory screen)
     * or world mode (hotbar, held item).
     * This affects the projection matrix used for rendering.
     */
    public void setGuiMode(boolean guiMode) {
        this.currentGuiMode = guiMode;
        vertexConsumer.setGuiMode(guiMode);
    }

    /**
     * Check if currently in GUI mode.
     */
    public boolean isGuiMode() {
        return currentGuiMode;
    }

    /**
     * Set transform for the current item.
     */
    public void setItemTransform(Matrix4f position, Matrix3f normal) {
        positionMatrix.set(position);
        normalMatrix.set(normal);
        vertexConsumer.setTransforms(positionMatrix, normalMatrix);
    }

    /**
     * Set the current item texture.
     */
    public void setItemTexture(Identifier texture) {
        vertexConsumer.setTexture(texture != null ? texture.toString() : null);
    }

    /**
     * Get the vertex consumer for item rendering.
     */
    public VertexConsumer getVertexConsumer() {
        return vertexConsumer;
    }

    /**
     * Finish rendering the current item.
     */
    public void finishItem() {
        vertexConsumer.flushMesh();
        itemsRenderedThisFrame++;
        if (currentItemIs3DBlock) {
            blockItemsThisFrame++;
        } else {
            flatItemsThisFrame++;
        }
        // Reset for next item
        currentItemIs3DBlock = false;
    }

    /**
     * End item capture and prepare for rendering.
     */
    public void endCapture() {
        List<ItemVertexConsumer.CapturedMesh> meshes = vertexConsumer.harvest();

        for (ItemVertexConsumer.CapturedMesh mesh : meshes) {
            pendingMeshes.add(new ItemMesh(mesh.vertexData, mesh.vertexCount, mesh.textureId, mesh.is3DBlock));
            verticesRenderedThisFrame += mesh.vertexCount;
        }

        if (frameCount <= 10 || frameCount % 300 == 0) {
            MetalLogger.info("[MetalItemRenderer] Frame %d: %d items (%d blocks, %d flat), %d vertices",
                    frameCount, itemsRenderedThisFrame, blockItemsThisFrame, flatItemsThisFrame,
                    verticesRenderedThisFrame);
        }
    }

    /**
     * Render all captured items.
     * 
     * 3D block items use depth testing to render as proper 3D cubes.
     * Flat items (swords, diamonds) don't need depth testing as they're essentially
     * billboards.
     */
    public void renderItems() {
        if (frameCount <= 10 || frameCount % 300 == 0) {
            System.out.println("[MetalItemRenderer] renderItems called: enabled=" + enabled +
                    ", handle=" + (deviceHandle != 0) + ", meshes=" + pendingMeshes.size());
        }

        if (!enabled || deviceHandle == 0 || pendingMeshes.isEmpty())
            return;

        try {
            // Try to use the dedicated item pass if available, fallback to entity pass
            boolean useItemPass = tryBeginItemPass();

            if (frameCount <= 10 || frameCount % 300 == 0) {
                System.out.println(
                        "[MetalItemRenderer] Drawing " + pendingMeshes.size() + " meshes, useItemPass=" + useItemPass);
            }

            if (!useItemPass) {
                // Fallback: use entity pass
                NativeBridge.nBeginEntityPass(deviceHandle, viewProjection, cameraPos);
            }

            int drawnMeshes = 0;
            for (ItemMesh mesh : pendingMeshes) {
                if (mesh.vertexCount <= 0)
                    continue;

                long textureHandle = 0;
                if (mesh.textureId != null) {
                    textureHandle = EntityTextureCache.getInstance().getOrCreateTexture(mesh.textureId);
                }

                if (frameCount <= 10 || frameCount % 300 == 0) {
                    System.out.println("[MetalItemRenderer] Mesh " + drawnMeshes + ": verts=" + mesh.vertexCount +
                            ", texture=" + mesh.textureId + ", handle=" + textureHandle + ", is3D=" + mesh.is3DBlock);
                }

                // Use the new nDrawItem if available, with depth testing for 3D blocks
                if (useItemPass) {
                    try {
                        NativeBridge.nDrawItem(deviceHandle, mesh.vertexData, mesh.vertexCount, textureHandle,
                                mesh.is3DBlock);
                    } catch (UnsatisfiedLinkError e) {
                        // Fallback to standard entity draw
                        NativeBridge.nDrawEntity(deviceHandle, mesh.vertexData, mesh.vertexCount, textureHandle);
                    }
                } else {
                    NativeBridge.nDrawEntity(deviceHandle, mesh.vertexData, mesh.vertexCount, textureHandle);
                }
                drawnMeshes++;
            }

            if (useItemPass) {
                tryEndItemPass();
            } else {
                NativeBridge.nEndEntityPass(deviceHandle);
            }

        } catch (UnsatisfiedLinkError e) {
            if (frameCount <= 5) {
                MetalLogger.warn("[MetalItemRenderer] Native not available: {}", e.getMessage());
            }
        }

        pendingMeshes.clear();
    }

    /**
     * Try to begin item pass. Returns true if successful, false if native method
     * not available.
     */
    private boolean tryBeginItemPass() {
        try {
            NativeBridge.nBeginItemPass(deviceHandle, viewProjection, cameraPos);
            return true;
        } catch (UnsatisfiedLinkError e) {
            return false;
        }
    }

    /**
     * Try to end item pass. Silently fails if native method not available.
     */
    private void tryEndItemPass() {
        try {
            NativeBridge.nEndItemPass(deviceHandle);
        } catch (UnsatisfiedLinkError ignored) {
        }
    }

    /**
     * Get items rendered this frame.
     */
    public int getItemsRenderedThisFrame() {
        return itemsRenderedThisFrame;
    }

    /**
     * Get vertices rendered this frame.
     */
    public int getVerticesRenderedThisFrame() {
        return verticesRenderedThisFrame;
    }

    /**
     * Clean up resources.
     */
    public void destroy() {
        enabled = false;
        vertexConsumer.beginFrame();
        pendingMeshes.clear();
        deviceHandle = 0;
    }

    /**
     * An item mesh for rendering.
     */
    public static class ItemMesh {
        public final ByteBuffer vertexData;
        public final int vertexCount;
        public final String textureId;
        public final boolean is3DBlock;

        public ItemMesh(ByteBuffer data, int count, String texture, boolean is3DBlock) {
            this.vertexData = data;
            this.vertexCount = count;
            this.textureId = texture;
            this.is3DBlock = is3DBlock;
        }
    }

    /**
     * Vertex consumer for item rendering.
     */
    public static class ItemVertexConsumer implements VertexConsumer {

        private ByteBuffer buffer;
        private int vertexCount = 0;
        private final List<CapturedMesh> capturedMeshes = new ArrayList<>();
        private String currentTexture = null;
        private boolean is3DBlock = false;
        private boolean guiMode = false;

        private final Matrix4f positionMatrix = new Matrix4f();
        private final Matrix3f normalMatrix = new Matrix3f();

        private float posX, posY, posZ;
        private float u, v;
        private int color = 0xFFFFFFFF;
        private int packedNormal = 0x007F7F7F;
        private boolean hasVertex = false;

        public ItemVertexConsumer() {
            allocateBuffer(INITIAL_CAPACITY);
        }

        private void allocateBuffer(int capacity) {
            buffer = ByteBuffer.allocateDirect(capacity * VERTEX_STRIDE)
                    .order(ByteOrder.nativeOrder());
        }

        public void setTransforms(Matrix4f position, Matrix3f normal) {
            positionMatrix.set(position);
            normalMatrix.set(normal);
        }

        public void setTexture(String texture) {
            if (currentTexture != null && !currentTexture.equals(texture) && vertexCount > 0) {
                flushMesh();
            }
            currentTexture = texture;
        }

        public void setIs3DBlock(boolean is3DBlock) {
            this.is3DBlock = is3DBlock;
        }

        public void setGuiMode(boolean guiMode) {
            this.guiMode = guiMode;
        }

        public void beginFrame() {
            if (hasVertex)
                emitVertex();
            buffer.clear();
            vertexCount = 0;
            capturedMeshes.clear();
            currentTexture = null;
            is3DBlock = false;
            positionMatrix.identity();
            normalMatrix.identity();
            hasVertex = false;
        }

        public void flushMesh() {
            if (hasVertex) {
                emitVertex();
                hasVertex = false;
            }

            if (vertexCount == 0)
                return;

            buffer.flip();
            ByteBuffer copy = ByteBuffer.allocateDirect(buffer.remaining())
                    .order(ByteOrder.nativeOrder());
            copy.put(buffer);
            copy.flip();

            capturedMeshes.add(new CapturedMesh(copy, vertexCount, currentTexture, is3DBlock));

            buffer.clear();
            vertexCount = 0;
        }

        public List<CapturedMesh> harvest() {
            flushMesh();
            List<CapturedMesh> result = new ArrayList<>(capturedMeshes);
            capturedMeshes.clear();
            return result;
        }

        private static int vertexCallCount = 0;

        @Override
        public VertexConsumer vertex(float x, float y, float z) {
            if (hasVertex)
                emitVertex();
            posX = x;
            posY = y;
            posZ = z;
            hasVertex = true;

            // Debug logging
            vertexCallCount++;
            if (vertexCallCount <= 20) {
                System.out.println(
                        "[ItemVertexConsumer] vertex(" + x + ", " + y + ", " + z + ") call #" + vertexCallCount);
            }

            return this;
        }

        @Override
        public VertexConsumer color(int red, int green, int blue, int alpha) {
            color = (alpha << 24) | (blue << 16) | (green << 8) | red;
            return this;
        }

        @Override
        public VertexConsumer color(int argb) {
            color = argb;
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
            packedNormal = nx | (ny << 10) | (nz << 20);
            return this;
        }

        @Override
        public VertexConsumer lineWidth(float width) {
            // Line width is not used for item rendering
            return this;
        }

        private void emitVertex() {
            if (vertexCount >= MAX_VERTICES - 1) {
                flushMesh();
            }

            if (buffer.remaining() < VERTEX_STRIDE) {
                ByteBuffer newBuffer = ByteBuffer.allocateDirect(buffer.capacity() * 2)
                        .order(ByteOrder.nativeOrder());
                buffer.flip();
                newBuffer.put(buffer);
                buffer = newBuffer;
            }

            Vector4f worldPos = new Vector4f(posX, posY, posZ, 1.0f);
            worldPos.mul(positionMatrix);

            buffer.putFloat(worldPos.x);
            buffer.putFloat(worldPos.y);
            buffer.putFloat(worldPos.z);
            buffer.putFloat(u);
            buffer.putFloat(v);
            buffer.putInt(color);
            buffer.putInt(packedNormal);
            buffer.putInt(0);

            vertexCount++;
            resetVertex();
        }

        private void resetVertex() {
            posX = posY = posZ = 0;
            u = v = 0;
            color = 0xFFFFFFFF;
            packedNormal = 0x007F7F7F;
            hasVertex = false;
        }

        public static class CapturedMesh {
            public final ByteBuffer vertexData;
            public final int vertexCount;
            public final String textureId;
            public final boolean is3DBlock;

            public CapturedMesh(ByteBuffer data, int count, String texture, boolean is3DBlock) {
                this.vertexData = data;
                this.vertexCount = count;
                this.textureId = texture;
                this.is3DBlock = is3DBlock;
            }
        }
    }
}
