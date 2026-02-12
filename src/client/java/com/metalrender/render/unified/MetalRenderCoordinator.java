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

public class MetalRenderCoordinator {

    private static final MetalRenderCoordinator INSTANCE = new MetalRenderCoordinator();

    
    private long deviceHandle = 0;

    
    private volatile boolean initialized = false;

    
    private final MetalEntityRenderer entityRenderer = MetalEntityRenderer.getInstance();
    private final MetalGuiRenderer guiRenderer = MetalGuiRenderer.getInstance();
    private final MetalItemRenderer itemRenderer = MetalItemRenderer.getInstance();
    private final MetalParticleRenderer particleRenderer = MetalParticleRenderer.getInstance();
    private final MetalTextRenderer textRenderer = MetalTextRenderer.getInstance();
    private final MetalTextureManager textureManager = MetalTextureManager.getInstance();
    private final MetalRenderBatch renderBatch = MetalRenderBatch.getInstance();

    
    private final float[] viewProjection = new float[16];
    private float cameraX, cameraY, cameraZ;
    private int screenWidth, screenHeight;

    
    private int frameCount = 0;
    private long lastFrameTime = 0;
    private float averageFrameTime = 0;

    
    private boolean terrainRendered = false;
    private boolean entitiesRendered = false;
    private boolean weatherRendered = false;
    private boolean guiRendered = false;

    private MetalRenderCoordinator() {
    }

    public static MetalRenderCoordinator getInstance() {
        return INSTANCE;
    }

    
    public void initialize(long device) {
        this.deviceHandle = device;
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

    
    public void beginFrame(Camera camera, Matrix4f viewProj) {
        if (!initialized)
            return;

        frameCount++;
        lastFrameTime = System.nanoTime();
        viewProj.get(viewProjection);
        net.minecraft.util.math.Vec3d pos = camera.getCameraPos();
        cameraX = (float) pos.x;
        cameraY = (float) pos.y;
        cameraZ = (float) pos.z;
        MinecraftClient mc = MinecraftClient.getInstance();
        screenWidth = mc.getWindow().getFramebufferWidth();
        screenHeight = mc.getWindow().getFramebufferHeight();
        terrainRendered = false;
        entitiesRendered = false;
        weatherRendered = false;
        guiRendered = false;
        entityRenderer.beginFrame(camera, viewProj);
        if (!EntityCaptureState.isGuiMode()) {
            itemRenderer.beginFrame(viewProj, cameraX, cameraY, cameraZ);
        }
        Matrix4f viewMatrix = new Matrix4f();
        viewMatrix.set(viewProj);
        particleRenderer.beginFrame(viewProj, viewMatrix, cameraX, cameraY, cameraZ);

        renderBatch.beginFrame(viewProj, cameraX, cameraY, cameraZ);

        if (frameCount <= 10 || frameCount % 300 == 0) {
            MetalLogger.info("[MetalRenderCoordinator] Frame {} begin: camera at ({}, {}, {})",
                    frameCount, cameraX, cameraY, cameraZ);
        }
    }

    
    public void onTerrainRendered() {
        terrainRendered = true;
    }

    
    public void renderEntities() {
        if (!initialized || entitiesRendered)
            return;

        entityRenderer.endCapture();
        entityRenderer.renderEntities();
        entitiesRendered = true;
    }

    
    public void renderItems() {
        if (!initialized)
            return;

        itemRenderer.endCapture();
        itemRenderer.renderItems();
    }

    
    public void renderParticles() {
        if (!initialized)
            return;

        particleRenderer.endFrame();
    }

    
    public void renderWeather() {
        if (!initialized || weatherRendered)
            return;

        entityRenderer.endCapture();
        entityRenderer.renderEntities();
        weatherRendered = true;
    }

    
    public boolean isWeatherRendered() {
        return weatherRendered;
    }

    
    public void beginGui() {
        if (!initialized)
            return;

        guiRenderer.beginFrame(screenWidth, screenHeight);
    }

    
    public void endGui() {
        if (!initialized || guiRendered)
            return;

        guiRenderer.endFrame();
        guiRendered = true;
    }

    
    public void flushAll() {
        if (!initialized)
            return;

        renderBatch.flush();
    }

    
    public void endFrame() {
        if (!initialized)
            return;
        long now = System.nanoTime();
        float frameTime = (now - lastFrameTime) / 1_000_000.0f; 
        averageFrameTime = averageFrameTime * 0.9f + frameTime * 0.1f;

        if (frameCount <= 10 || frameCount % 300 == 0) {
            MetalLogger.info("[MetalRenderCoordinator] Frame {} end: {:.2f}ms (avg: {:.2f}ms)",
                    frameCount, frameTime, averageFrameTime);
            MetalLogger.info("  Entities: {} vertices, Items: {} vertices, Particles: {} captured",
                    entityRenderer.getVerticesRenderedThisFrame(),
                    itemRenderer.getVerticesRenderedThisFrame(),
                    com.metalrender.render.particle.ParticleCaptureState.getAndResetFrameCount());
        }
    }

    
    public MetalEntityRenderer getEntityRenderer() {
        return entityRenderer;
    }

    
    public MetalGuiRenderer getGuiRenderer() {
        return guiRenderer;
    }

    
    public MetalItemRenderer getItemRenderer() {
        return itemRenderer;
    }

    
    public MetalParticleRenderer getParticleRenderer() {
        return particleRenderer;
    }

    
    public MetalTextRenderer getTextRenderer() {
        return textRenderer;
    }

    
    public MetalTextureManager getTextureManager() {
        return textureManager;
    }

    
    public MetalRenderBatch getRenderBatch() {
        return renderBatch;
    }

    
    public int getFrameCount() {
        return frameCount;
    }

    
    public float getAverageFrameTime() {
        return averageFrameTime;
    }

    
    public boolean isTerrainRendered() {
        return terrainRendered;
    }

    
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
