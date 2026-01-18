package com.metalrender.render.unified;

import com.metalrender.entity.EntityCaptureState;
import com.metalrender.entity.MetalEntityRenderer;
import com.metalrender.nativebridge.NativeBridge;
import com.metalrender.render.gui.MetalGuiRenderer;
import com.metalrender.render.item.MetalItemRenderer;
import com.metalrender.render.particle.MetalParticleRenderer;
import com.metalrender.render.text.MetalTextRenderer;
import com.metalrender.render.texture.MetalTextureManager;
import com.metalrender.util.MetalLogger;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import org.joml.Matrix4f;

/**
 * Unified render coordinator for all Metal rendering.
 * 
 * This class coordinates all Metal render types and ensures
 * proper ordering and state management:
 * 
 * Render order:
 * 1. Sky (if Metal sky enabled)
 * 2. Terrain (via Sodium integration)
 * 3. Block entities
 * 4. Entities
 * 5. Items (world)
 * 6. Particles
 * 7. Weather
 * 8. Translucent entities
 * 9. World border
 * 10. GUI
 * 11. Text
 */
public class MetalRenderCoordinator {

    private static final MetalRenderCoordinator INSTANCE = new MetalRenderCoordinator();

    /** Native device handle */
    private long deviceHandle = 0;

    /** Whether coordinator is initialized */
    private volatile boolean initialized = false;

    /** Sub-renderers */
    private final MetalEntityRenderer entityRenderer = MetalEntityRenderer.getInstance();
    private final MetalGuiRenderer guiRenderer = MetalGuiRenderer.getInstance();
    private final MetalItemRenderer itemRenderer = MetalItemRenderer.getInstance();
    private final MetalParticleRenderer particleRenderer = MetalParticleRenderer.getInstance();
    private final MetalTextRenderer textRenderer = MetalTextRenderer.getInstance();
    private final MetalTextureManager textureManager = MetalTextureManager.getInstance();
    private final MetalRenderBatch renderBatch = MetalRenderBatch.getInstance();

    /** Current frame state */
    private final float[] viewProjection = new float[16];
    private float cameraX, cameraY, cameraZ;
    private int screenWidth, screenHeight;

    /** Frame counters */
    private int frameCount = 0;
    private long lastFrameTime = 0;
    private float averageFrameTime = 0;

    /** Render state flags */
    private boolean terrainRendered = false;
    private boolean entitiesRendered = false;
    private boolean guiRendered = false;

    private MetalRenderCoordinator() {
    }

    public static MetalRenderCoordinator getInstance() {
        return INSTANCE;
    }

    /**
     * Initialize all Metal renderers.
     */
    public void initialize(long device) {
        this.deviceHandle = device;

        // Initialize all sub-renderers
        entityRenderer.initialize(device);
        guiRenderer.initialize(device);
        itemRenderer.initialize(device);
        particleRenderer.initialize(device);
        textRenderer.initialize(device);
        textureManager.initialize(device);
        renderBatch.initialize(device);

        initialized = true;
        MetalLogger.info("[MetalRenderCoordinator] Initialized all renderers with device: {}", device);
    }

    public boolean isInitialized() {
        return initialized && deviceHandle != 0;
    }

    /**
     * Begin a new render frame.
     * Called at the start of world rendering.
     */
    public void beginFrame(Camera camera, Matrix4f viewProj) {
        if (!initialized)
            return;

        frameCount++;
        lastFrameTime = System.nanoTime();

        // Store frame data
        viewProj.get(viewProjection);
        net.minecraft.util.math.Vec3d pos = camera.getCameraPos();
        cameraX = (float) pos.x;
        cameraY = (float) pos.y;
        cameraZ = (float) pos.z;

        // Get screen dimensions
        MinecraftClient mc = MinecraftClient.getInstance();
        screenWidth = mc.getWindow().getFramebufferWidth();
        screenHeight = mc.getWindow().getFramebufferHeight();

        // Reset render state
        terrainRendered = false;
        entitiesRendered = false;
        guiRendered = false;

        // Begin sub-renderer frames
        entityRenderer.beginFrame(camera, viewProj);
        // Only reset item renderer if NOT in GUI mode - GUI/screen item rendering
        // manages its own beginFrame/endCapture cycle via ScreenMixin
        if (!EntityCaptureState.isGuiMode()) {
            itemRenderer.beginFrame(viewProj, cameraX, cameraY, cameraZ);
        }

        // Get view matrix for particles
        Matrix4f viewMatrix = new Matrix4f();
        viewMatrix.set(viewProj);
        // Note: viewMatrix should be extracted properly, but for billboarding
        // we can derive it from the camera
        particleRenderer.beginFrame(viewProj, viewMatrix, cameraX, cameraY, cameraZ);

        renderBatch.beginFrame(viewProj, cameraX, cameraY, cameraZ);

        if (frameCount <= 10 || frameCount % 300 == 0) {
            MetalLogger.info("[MetalRenderCoordinator] Frame {} begin: camera at ({}, {}, {})",
                    frameCount, cameraX, cameraY, cameraZ);
        }
    }

    /**
     * Called after terrain rendering completes.
     */
    public void onTerrainRendered() {
        terrainRendered = true;
    }

    /**
     * Render all entities via Metal.
     * Called after entity vertex capture completes.
     */
    public void renderEntities() {
        if (!initialized || entitiesRendered)
            return;

        entityRenderer.endCapture();
        entityRenderer.renderEntities();
        entitiesRendered = true;
    }

    /**
     * Render all items via Metal.
     */
    public void renderItems() {
        if (!initialized)
            return;

        itemRenderer.endCapture();
        itemRenderer.renderItems();
    }

    /**
     * Render all particles via Metal.
     */
    public void renderParticles() {
        if (!initialized)
            return;

        particleRenderer.endFrame();
    }

    /**
     * Begin GUI rendering.
     */
    public void beginGui() {
        if (!initialized)
            return;

        guiRenderer.beginFrame(screenWidth, screenHeight);
    }

    /**
     * End GUI rendering.
     */
    public void endGui() {
        if (!initialized || guiRendered)
            return;

        guiRenderer.endFrame();
        guiRendered = true;
    }

    /**
     * Flush all pending render batches.
     */
    public void flushAll() {
        if (!initialized)
            return;

        renderBatch.flush();
    }

    /**
     * End the current frame.
     */
    public void endFrame() {
        if (!initialized)
            return;

        // Calculate frame time
        long now = System.nanoTime();
        float frameTime = (now - lastFrameTime) / 1_000_000.0f; // ms
        averageFrameTime = averageFrameTime * 0.9f + frameTime * 0.1f;

        if (frameCount <= 10 || frameCount % 300 == 0) {
            MetalLogger.info("[MetalRenderCoordinator] Frame {} end: {:.2f}ms (avg: {:.2f}ms)",
                    frameCount, frameTime, averageFrameTime);
            MetalLogger.info("  Entities: {} vertices, Items: {} vertices, Particles: {}",
                    entityRenderer.getVerticesRenderedThisFrame(),
                    itemRenderer.getVerticesRenderedThisFrame(),
                    particleRenderer.getTotalParticles());
        }
    }

    /**
     * Get the entity renderer.
     */
    public MetalEntityRenderer getEntityRenderer() {
        return entityRenderer;
    }

    /**
     * Get the GUI renderer.
     */
    public MetalGuiRenderer getGuiRenderer() {
        return guiRenderer;
    }

    /**
     * Get the item renderer.
     */
    public MetalItemRenderer getItemRenderer() {
        return itemRenderer;
    }

    /**
     * Get the particle renderer.
     */
    public MetalParticleRenderer getParticleRenderer() {
        return particleRenderer;
    }

    /**
     * Get the text renderer.
     */
    public MetalTextRenderer getTextRenderer() {
        return textRenderer;
    }

    /**
     * Get the texture manager.
     */
    public MetalTextureManager getTextureManager() {
        return textureManager;
    }

    /**
     * Get the render batch manager.
     */
    public MetalRenderBatch getRenderBatch() {
        return renderBatch;
    }

    /**
     * Get frame count.
     */
    public int getFrameCount() {
        return frameCount;
    }

    /**
     * Get average frame time in ms.
     */
    public float getAverageFrameTime() {
        return averageFrameTime;
    }

    /**
     * Check if terrain was rendered this frame.
     */
    public boolean isTerrainRendered() {
        return terrainRendered;
    }

    /**
     * Clean up all resources.
     */
    public void destroy() {
        entityRenderer.destroy();
        guiRenderer.destroy();
        itemRenderer.destroy();
        particleRenderer.destroy();
        textRenderer.destroy();
        textureManager.destroy();
        renderBatch.destroy();

        initialized = false;
        deviceHandle = 0;

        MetalLogger.info("[MetalRenderCoordinator] Destroyed all renderers");
    }
}
