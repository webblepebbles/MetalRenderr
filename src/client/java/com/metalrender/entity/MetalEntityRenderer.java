package com.metalrender.entity;

import com.metalrender.nativebridge.NativeBridge;
import com.metalrender.util.MetalLogger;
import net.minecraft.client.render.Camera;
import net.minecraft.util.Identifier;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages Metal-side entity rendering.
 * 
 * This class coordinates:
 * - MetalVertexConsumer for capturing entity vertex data
 * - EntityTextureCache for managing entity textures
 * - Native calls to render entities in Metal
 */
public class MetalEntityRenderer {

    private static final MetalEntityRenderer INSTANCE = new MetalEntityRenderer();

    /** The shared vertex consumer for capturing entity data */
    private final MetalVertexConsumer vertexConsumer = new MetalVertexConsumer();

    /** Texture cache for entity textures */
    private final EntityTextureCache textureCache = EntityTextureCache.getInstance();

    /** Pending entity meshes for current frame */
    private final List<MetalVertexConsumer.CapturedMesh> pendingMeshes = new ArrayList<>();

    /** Native device handle */
    private long deviceHandle = 0;

    /** Whether entity rendering is enabled */
    private volatile boolean enabled = false;

    /** Current frame's camera data */
    private float[] viewProjection = new float[16];
    private float[] cameraPos = new float[4];

    /** Temporary override projection for special rendering (e.g., hand) */
    private float[] overrideProjection = null;

    /** Debug counters */
    private int entitiesRenderedThisFrame = 0;
    private int verticesRenderedThisFrame = 0;

    private MetalEntityRenderer() {
    }

    public static MetalEntityRenderer getInstance() {
        return INSTANCE;
    }

    /**
     * Initialize with the Metal device handle.
     */
    public void initialize(long device) {
        this.deviceHandle = device;
        this.textureCache.initialize(device);
        this.enabled = true;
        MetalLogger.info("[MetalEntityRenderer] Initialized with device handle: {}", device);
    }

    /**
     * Check if entity rendering is enabled and ready.
     */
    public boolean isEnabled() {
        return enabled && deviceHandle != 0;
    }

    private static int beginFrameCount = 0;

    /**
     * Begin a new frame of entity rendering.
     * Called at the start of world rendering.
     */
    public void beginFrame(Camera camera, Matrix4f viewProj) {
        beginFrameCount++;
        if (!enabled)
            return;

        // Reset state
        vertexConsumer.beginFrame();
        pendingMeshes.clear();
        entitiesRenderedThisFrame = 0;
        verticesRenderedThisFrame = 0;

        // Store camera data for shaders
        viewProj.get(viewProjection);

        // Debug: Log viewProj matrix
        if (beginFrameCount <= 5) {
            System.out.println("[MetalEntityRenderer] beginFrame #" + beginFrameCount +
                    " viewProj diagonal: [" + viewProjection[0] + ", " + viewProjection[5] +
                    ", " + viewProjection[10] + ", " + viewProjection[15] + "]");
            System.out.println("[MetalEntityRenderer]   viewProj translation: [" +
                    viewProjection[12] + ", " + viewProjection[13] + ", " + viewProjection[14] + "]");
        }

        // NOTE: Entity vertices are already in camera-relative space after
        // transforming by positionMatrix from MatrixStack. So we pass zero
        // as cameraPos to avoid subtracting it again in the shader.
        cameraPos[0] = 0.0f;
        cameraPos[1] = 0.0f;
        cameraPos[2] = 0.0f;
        cameraPos[3] = 1.0f;
    }

    /**
     * Get the vertex consumer for entity rendering.
     * Entity renderers will use this to output their vertices.
     */
    public MetalVertexConsumer getVertexConsumer() {
        return vertexConsumer;
    }

    /**
     * Set the current entity's transform matrices.
     * Called before rendering each entity.
     */
    public void setEntityTransform(Matrix4f positionMatrix, Matrix3f normalMatrix) {
        vertexConsumer.setTransforms(positionMatrix, normalMatrix);
    }

    /**
     * Set the current entity's texture.
     * Called before rendering each entity.
     */
    public void setEntityTexture(Identifier texture) {
        vertexConsumer.setTexture(texture.toString());
    }

    /**
     * Called after an entity finishes rendering to flush its vertices.
     */
    public void finishEntity() {
        vertexConsumer.flushMesh();
        entitiesRenderedThisFrame++;
    }

    private static int endCaptureCount = 0;

    /**
     * End entity capture and prepare for Metal rendering.
     * Called after all entities have been processed.
     */
    public void endCapture() {
        endCaptureCount++;

        // Harvest all captured meshes
        List<MetalVertexConsumer.CapturedMesh> meshes = vertexConsumer.harvest();

        if (endCaptureCount <= 50 || endCaptureCount % 100 == 0) {
            System.out.println("[MetalEntityRenderer] endCapture #" + endCaptureCount +
                    ": harvested " + meshes.size() + " meshes");
            for (MetalVertexConsumer.CapturedMesh m : meshes) {
                System.out.println("  - mesh: " + m.vertexCount + " verts, tex=" + m.textureId);
            }
        }

        pendingMeshes.addAll(meshes);

        // Count vertices
        for (MetalVertexConsumer.CapturedMesh mesh : meshes) {
            verticesRenderedThisFrame += mesh.vertexCount;
        }
    }

    private static int renderEntitiesCallCount = 0;

    /**
     * Execute Metal rendering of all captured entities.
     * Called during the Metal render pass, after terrain.
     */
    public void renderEntities() {
        renderEntitiesCallCount++;

        if (!enabled || deviceHandle == 0) {
            if (renderEntitiesCallCount <= 50) {
                System.out.println("[MetalEntityRenderer] renderEntities #" + renderEntitiesCallCount +
                        ": EARLY EXIT - enabled=" + enabled + " deviceHandle=" + deviceHandle);
            }
            return;
        }

        if (pendingMeshes.isEmpty()) {
            if (renderEntitiesCallCount <= 50) {
                System.out.println("[MetalEntityRenderer] renderEntities #" + renderEntitiesCallCount +
                        ": pendingMeshes is EMPTY");
            }
            // Even with no meshes, call the pass for consistency
            try {
                NativeBridge.nBeginEntityPass(deviceHandle, viewProjection, cameraPos);
                NativeBridge.nEndEntityPass(deviceHandle);
            } catch (Throwable e) {
                // Silently ignore
            }
            return;
        }

        if (renderEntitiesCallCount <= 50) {
            System.out.println("[MetalEntityRenderer] renderEntities #" + renderEntitiesCallCount +
                    ": RENDERING " + pendingMeshes.size() + " meshes");
        }

        try {
            // Begin entity render pass with frame uniforms
            // Use override projection if set (for hand rendering), otherwise use world
            // projection
            float[] projectionToUse = (overrideProjection != null) ? overrideProjection : viewProjection;
            NativeBridge.nBeginEntityPass(deviceHandle, projectionToUse, cameraPos);

            // Render each mesh batch
            int meshIndex = 0;
            for (MetalVertexConsumer.CapturedMesh mesh : pendingMeshes) {
                if (mesh.vertexCount <= 0)
                    continue;

                // Get texture handle from cache
                long textureHandle = 0;
                if (mesh.textureId != null) {
                    textureHandle = textureCache.getOrCreateTexture(mesh.textureId);
                }

                if (renderEntitiesCallCount <= 10) {
                    System.out.println("[MetalEntityRenderer] About to draw mesh #" + meshIndex +
                            ": verts=" + mesh.vertexCount + " texHandle=" + textureHandle + " texId=" + mesh.textureId);
                }

                NativeBridge.nDrawEntity(deviceHandle, mesh.vertexData, mesh.vertexCount, textureHandle);
                meshIndex++;
            }

            // End entity render pass
            NativeBridge.nEndEntityPass(deviceHandle);

        } catch (Throwable e) {
            // Log errors only
            System.err.println("[MetalEntityRenderer] ERROR: " + e.getMessage());
        }

        // Clear pending meshes after rendering
        pendingMeshes.clear();
    }

    /**
     * Get debug statistics.
     */
    public int getEntitiesRenderedThisFrame() {
        return entitiesRenderedThisFrame;
    }

    public int getVerticesRenderedThisFrame() {
        return verticesRenderedThisFrame;
    }

    /**
     * Set a custom projection matrix for special rendering like hand/arm.
     * Pass null to clear and use world projection.
     */
    public void setOverrideProjection(Matrix4f projection) {
        if (projection != null) {
            if (overrideProjection == null) {
                overrideProjection = new float[16];
            }
            projection.get(overrideProjection);
        } else {
            overrideProjection = null;
        }
    }

    /**
     * Clean up resources.
     */
    public void destroy() {
        enabled = false;
        textureCache.clear();
        vertexConsumer.beginFrame(); // Clear any pending data
        pendingMeshes.clear();
        MetalLogger.info("[MetalEntityRenderer] Destroyed");
    }
}
