package com.pebbles_boon.metalrender.particle;
import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.backend.MetalRenderer;
import com.pebbles_boon.metalrender.nativebridge.NativeBridge;
import com.pebbles_boon.metalrender.render.CapturedMatrices;
import com.pebbles_boon.metalrender.sodium.mixins.accessor.BillboardParticleAccessor;
import com.pebbles_boon.metalrender.sodium.mixins.accessor.ParticleAccessor;
import com.pebbles_boon.metalrender.util.MetalLogger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.particle.BillboardParticle;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleManager;
import net.minecraft.client.particle.ParticleRenderer;
import net.minecraft.client.particle.ParticleTextureSheet;
import net.minecraft.client.render.Camera;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.texture.GlTexture;
import net.minecraft.client.texture.Sprite;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
public class MetalParticleRenderer {
  private static final int VERTEX_STRIDE = 32;
  private static final int MAX_PARTICLE_VERTICES = 65536 * 6;
  private static final int MAX_PARTICLES_PER_FRAME = 65536;
  private long deviceHandle;
  private boolean active;
  private int frameCount;
  private ByteBuffer vertexStagingBuffer;
  private long metalVertexBuffer;
  private int currentVertexCount;
  private final List<ParticleDrawCommand> pendingDraws = new ArrayList<>();
  private final List<CapturedParticle> capturedParticles = new ArrayList<>();
  private final ConcurrentHashMap<Integer, Long> textureCache =
      new ConcurrentHashMap<>();
  private final ConcurrentHashMap<Integer, Integer> textureUploadFrame =
      new ConcurrentHashMap<>();
  private static final int ATLAS_REFRESH_FRAMES = 4;
  public MetalParticleRenderer() {
    vertexStagingBuffer =
        ByteBuffer.allocateDirect(MAX_PARTICLE_VERTICES * VERTEX_STRIDE)
            .order(ByteOrder.nativeOrder());
  }
  public void setDeviceAndPipeline(long device) {
    this.deviceHandle = device;
    if (device != 0) {
      metalVertexBuffer = NativeBridge.nCreateBuffer(
          device, MAX_PARTICLE_VERTICES * VERTEX_STRIDE, 0);
      MetalLogger.info("MetalParticleRenderer initialized: device=%d vb=%d",
                       device, metalVertexBuffer);
    }
  }
  public void setActive(boolean active) { this.active = active; }
  public boolean isActive() { return active; }
  public void captureParticles(ParticleManager particleManager, Camera camera,
                               float tickDelta) {
    if (!active)
      return;
    capturedParticles.clear();
    try {
      captureFromManager(particleManager, camera, tickDelta);
    } catch (Exception e) {
      if (frameCount < 5) {
        MetalLogger.error("Failed to capture particles: %s", e.getMessage());
        e.printStackTrace();
      }
    }
  }
  public void captureParticleList(Queue<? extends Particle> particles,
                                  Camera camera, float tickDelta) {
    if (!active || particles == null)
      return;
    double camX = camera.getCameraPos().x;
    double camY = camera.getCameraPos().y;
    double camZ = camera.getCameraPos().z;
    int captured = 0;
    for (Particle p : particles) {
      if (capturedParticles.size() >= MAX_PARTICLES_PER_FRAME)
        break;
      if (p == null || !p.isAlive())
        continue;
      if (!(p instanceof BillboardParticle bp))
        continue;
      captured++;
      CapturedParticle cp = new CapturedParticle();
      ParticleAccessor pa = (ParticleAccessor)p;
      BillboardParticleAccessor bpa = (BillboardParticleAccessor)bp;
      cp.x = (float)(MathHelper.lerp(tickDelta, pa.metalrender$getLastX(),
                                     pa.metalrender$getX()) -
                     camX);
      cp.y = (float)(MathHelper.lerp(tickDelta, pa.metalrender$getLastY(),
                                     pa.metalrender$getY()) -
                     camY);
      cp.z = (float)(MathHelper.lerp(tickDelta, pa.metalrender$getLastZ(),
                                     pa.metalrender$getZ()) -
                     camZ);
      cp.scale = bp.getSize(tickDelta);
      cp.red = bpa.metalrender$getRed();
      cp.green = bpa.metalrender$getGreen();
      cp.blue = bpa.metalrender$getBlue();
      cp.alpha = bpa.metalrender$getAlpha();
      cp.zRotation =
          MathHelper.lerp(tickDelta, bpa.metalrender$getLastZRotation(),
                          bpa.metalrender$getZRotation());
      Sprite sprite = bpa.metalrender$getSprite();
      if (sprite != null) {
        cp.minU = sprite.getMinU();
        cp.maxU = sprite.getMaxU();
        cp.minV = sprite.getMinV();
        cp.maxV = sprite.getMaxV();
        if (sprite.getAtlasId() != null) {
          cp.atlasId = sprite.getAtlasId();
        }
      } else {
        cp.minU = 0;
        cp.maxU = 1;
        cp.minV = 0;
        cp.maxV = 1;
      }
      cp.light = 0x00F000F0;
      capturedParticles.add(cp);
    }
    if (frameCount < 5 && captured > 0) {
      MetalLogger.info("Captured %d billboard particles from queue", captured);
    }
  }
  private void captureFromManager(ParticleManager manager, Camera camera,
                                  float tickDelta) {
  }
  public void renderCapturedParticles(long frameContext) {
    if (!active || frameContext == 0 || deviceHandle == 0)
      return;
    if (capturedParticles.isEmpty()) {
      frameCount++;
      return;
    }
    buildParticleGeometry();
    if (currentVertexCount == 0 || pendingDraws.isEmpty()) {
      capturedParticles.clear();
      frameCount++;
      return;
    }
    vertexStagingBuffer.flip();
    int uploadSize = currentVertexCount * VERTEX_STRIDE;
    if (metalVertexBuffer != 0 && uploadSize > 0) {
      byte[] data = new byte[uploadSize];
      vertexStagingBuffer.get(data);
      NativeBridge.nUploadBufferData(metalVertexBuffer, data, 0, uploadSize);
    }
    MetalRenderer renderer = MetalRenderClient.getRenderer();
    if (renderer == null) {
      frameCount++;
      return;
    }
    long particlePipeline =
        NativeBridge.nGetParticlePipelineHandle(renderer.getHandle());
    if (particlePipeline == 0) {
      particlePipeline = NativeBridge.nGetEntityTranslucentPipelineHandle(
          renderer.getHandle());
    }
    if (particlePipeline == 0) {
      if (frameCount < 5)
        MetalLogger.warn("No particle pipeline available");
      frameCount++;
      return;
    }
    NativeBridge.nSetPipelineState(frameContext, particlePipeline);
    NativeBridge.nSetChunkOffset(frameContext, 0.0f, 0.0f, 0.0f);
    NativeBridge.nSetEntityOverlay(frameContext, 0.0f, 0.0f, 1.0f);
    int drawsDone = 0;
    for (ParticleDrawCommand cmd : pendingDraws) {
      if (cmd.vertexCount <= 0)
        continue;
      if (cmd.glTextureId != 0) {
        long metalTex = getOrCreateMetalTexture(cmd.glTextureId);
        if (metalTex != 0) {
          NativeBridge.nBindEntityTexture(frameContext, metalTex);
        }
      }
      NativeBridge.nDrawEntityBuffer(frameContext, metalVertexBuffer,
                                     cmd.vertexCount, cmd.startVertex,
                                     0x8);
      drawsDone++;
    }
    frameCount++;
    if (frameCount <= 5 || frameCount % 500 == 0) {
      MetalLogger.info(
          "MetalParticleRenderer: frame %d, %d particles, %d verts, %d draws",
          frameCount, capturedParticles.size(), currentVertexCount, drawsDone);
    }
    pendingDraws.clear();
    capturedParticles.clear();
  }
  private void buildParticleGeometry() {
    vertexStagingBuffer.clear();
    currentVertexCount = 0;
    pendingDraws.clear();
    MinecraftClient client = MinecraftClient.getInstance();
    if (client == null)
      return;
    Camera camera = client.gameRenderer.getCamera();
    if (camera == null)
      return;
    Quaternionf camRot = camera.getRotation();
    int currentGlTexId = -1;
    int batchStartVertex = 0;
    int batchVertexCount = 0;
    for (CapturedParticle cp : capturedParticles) {
      if (currentVertexCount + 6 > MAX_PARTICLE_VERTICES)
        break;
      int glTexId = 0;
      if (cp.atlasId != null) {
        glTexId = getGlTextureIdForAtlas(cp.atlasId);
      }
      if (glTexId != currentGlTexId && batchVertexCount > 0) {
        ParticleDrawCommand cmd = new ParticleDrawCommand();
        cmd.startVertex = batchStartVertex;
        cmd.vertexCount = batchVertexCount;
        cmd.glTextureId = currentGlTexId;
        pendingDraws.add(cmd);
        batchStartVertex = currentVertexCount;
        batchVertexCount = 0;
      }
      currentGlTexId = glTexId;
      buildBillboardQuad(cp, camRot);
      batchVertexCount += 6;
    }
    if (batchVertexCount > 0) {
      ParticleDrawCommand cmd = new ParticleDrawCommand();
      cmd.startVertex = batchStartVertex;
      cmd.vertexCount = batchVertexCount;
      cmd.glTextureId = currentGlTexId;
      pendingDraws.add(cmd);
    }
    if (frameCount < 5 && currentVertexCount > 0) {
      MetalLogger.info("Built %d particle verts in %d draw batches",
                       currentVertexCount, pendingDraws.size());
    }
  }
  private void buildBillboardQuad(CapturedParticle cp, Quaternionf camRot) {
    float size = cp.scale;
    float x = cp.x, y = cp.y, z = cp.z;
    Quaternionf rot = new Quaternionf(camRot);
    if (cp.zRotation != 0.0f) {
      rot.rotateZ(cp.zRotation);
    }
    float[][] corners = {
        {-1.0f, -1.0f, 0.0f},
        {1.0f, -1.0f, 0.0f},
        {1.0f, 1.0f, 0.0f},
        {-1.0f, 1.0f, 0.0f}
    };
    float[] uCoords = {cp.maxU, cp.minU, cp.minU, cp.maxU};
    float[] vCoords = {cp.maxV, cp.maxV, cp.minV, cp.minV};
    Vector3f[] worldCorners = new Vector3f[4];
    for (int i = 0; i < 4; i++) {
      Vector3f v = new Vector3f(corners[i][0], corners[i][1], corners[i][2]);
      rot.transform(v);
      v.mul(size);
      v.add(x, y, z);
      worldCorners[i] = v;
    }
    int r = (int)(cp.red * 255.0f) & 0xFF;
    int g = (int)(cp.green * 255.0f) & 0xFF;
    int b = (int)(cp.blue * 255.0f) & 0xFF;
    int a = (int)(cp.alpha * 255.0f) & 0xFF;
    int color = (a << 24) | (r << 16) | (g << 8) | b;
    Vector3f normal = new Vector3f(0, 0, 1);
    camRot.transform(normal);
    int light = cp.light;
    int[] triIdx = {0, 1, 2, 0, 2, 3};
    for (int ti : triIdx) {
      writeParticleVertex(worldCorners[ti].x, worldCorners[ti].y,
                          worldCorners[ti].z, uCoords[ti], vCoords[ti], color,
                          normal.x, normal.y, normal.z, light);
    }
  }
  private void writeParticleVertex(float px, float py, float pz, float u,
                                   float v, int color, float nx, float ny,
                                   float nz, int light) {
    if (currentVertexCount >= MAX_PARTICLE_VERTICES ||
        vertexStagingBuffer.remaining() < VERTEX_STRIDE)
      return;
    vertexStagingBuffer.putFloat(px);
    vertexStagingBuffer.putFloat(py);
    vertexStagingBuffer.putFloat(pz);
    int iU = (int)(Math.min(Math.max(u, 0.0f), 1.0f) * 32767.0f);
    int iV = (int)(Math.min(Math.max(v, 0.0f), 1.0f) * 32767.0f);
    vertexStagingBuffer.putShort((short)(iU & 0x7FFF));
    vertexStagingBuffer.putShort((short)(iV & 0x7FFF));
    int cr = (color >> 16) & 0xFF;
    int cg = (color >> 8) & 0xFF;
    int cb = color & 0xFF;
    int ca = (color >> 24) & 0xFF;
    vertexStagingBuffer.put((byte)cr);
    vertexStagingBuffer.put((byte)cg);
    vertexStagingBuffer.put((byte)cb);
    vertexStagingBuffer.put((byte)ca);
    vertexStagingBuffer.put((byte)(int)((nx * 0.5f + 0.5f) * 255.0f));
    vertexStagingBuffer.put((byte)(int)((ny * 0.5f + 0.5f) * 255.0f));
    vertexStagingBuffer.put((byte)(int)((nz * 0.5f + 0.5f) * 255.0f));
    vertexStagingBuffer.put((byte)255);
    vertexStagingBuffer.putShort((short)0);
    vertexStagingBuffer.putShort((short)10);
    int blockL = (light & 0xFFFF) >> 4;
    int skyL = ((light >> 16) & 0xFFFF) >> 4;
    vertexStagingBuffer.putShort((short)(blockL & 0xFFFF));
    vertexStagingBuffer.putShort((short)(skyL & 0xFFFF));
    currentVertexCount++;
  }
  private long getOrCreateMetalTexture(int glTextureId) {
    if (glTextureId == 0 || deviceHandle == 0)
      return 0;
    Integer lastUpload = textureUploadFrame.get(glTextureId);
    boolean needsRefresh = (lastUpload == null) ||
                           (frameCount - lastUpload >= ATLAS_REFRESH_FRAMES);
    Long cached = textureCache.get(glTextureId);
    if (cached != null && !needsRefresh)
      return cached;
    try {
      int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, glTextureId);
      int width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0,
                                               GL11.GL_TEXTURE_WIDTH);
      int height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0,
                                                GL11.GL_TEXTURE_HEIGHT);
      if (width <= 0 || height <= 0 || width > 8192 || height > 8192) {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
        textureCache.put(glTextureId, 0L);
        return 0;
      }
      ByteBuffer pixels = BufferUtils.createByteBuffer(width * height * 4);
      GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA,
                         GL11.GL_UNSIGNED_BYTE, pixels);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
      byte[] pixelData = new byte[width * height * 4];
      pixels.get(pixelData);
      if (cached != null && cached != 0) {
      }
      long metalTex =
          NativeBridge.nCreateTexture2D(deviceHandle, width, height, pixelData);
      textureCache.put(glTextureId, metalTex);
      textureUploadFrame.put(glTextureId, frameCount);
      if (metalTex != 0 && frameCount < 3) {
        MetalLogger.info(
            "Created/refreshed Metal particle texture: glId=%d %dx%d handle=%d",
            glTextureId, width, height, metalTex);
      }
      return metalTex;
    } catch (Exception e) {
      MetalLogger.error("Failed to create Metal particle texture glId=%d: %s",
                        glTextureId, e.getMessage());
      textureCache.put(glTextureId, 0L);
      return 0;
    }
  }
  private int getGlTextureIdForAtlas(Identifier atlasId) {
    try {
      MinecraftClient client = MinecraftClient.getInstance();
      if (client == null)
        return 0;
      AbstractTexture tex = client.getTextureManager().getTexture(atlasId);
      if (tex == null)
        return 0;
      com.mojang.blaze3d.textures.GpuTexture gpuTex = tex.getGlTexture();
      if (gpuTex instanceof GlTexture glTex) {
        return glTex.getGlId();
      }
    } catch (Exception e) {
    }
    return 0;
  }
  public void invalidateTextureCache() {
    textureCache.clear();
    MetalLogger.info("Particle texture cache invalidated");
  }
  public int getLastParticleCount() { return capturedParticles.size(); }
  public int getLastVertexCount() { return currentVertexCount; }
  public void shutdown() {
    active = false;
    capturedParticles.clear();
    pendingDraws.clear();
    textureCache.clear();
    if (metalVertexBuffer != 0) {
      NativeBridge.nDestroyBuffer(metalVertexBuffer);
      metalVertexBuffer = 0;
    }
    deviceHandle = 0;
    MetalLogger.info("MetalParticleRenderer shut down");
  }
  private static class CapturedParticle {
    float x, y, z;
    float scale;
    float red, green, blue, alpha;
    float zRotation;
    float minU, maxU, minV, maxV;
    int light;
    Identifier atlasId;
  }
  private static class ParticleDrawCommand {
    int startVertex;
    int vertexCount;
    int glTextureId;
  }
}
