package com.pebbles_boon.metalrender.entity;

import com.pebbles_boon.metalrender.MetalRenderClient;
import com.pebbles_boon.metalrender.backend.MetalRenderer;
import com.pebbles_boon.metalrender.nativebridge.NativeBridge;
import com.pebbles_boon.metalrender.render.CapturedMatrices;
import com.pebbles_boon.metalrender.util.MetalLogger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.model.Model;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.entity.EntityRenderManager;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.model.EntityModel;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.texture.GlTexture;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

public class MetalEntityRenderer {


  private static final int ENTITY_VERTEX_STRIDE = 32;


  private static final int MAX_BATCH_VERTICES = 262144;


  private static final int MAX_ENTITIES_PER_FRAME = 512;

  private long deviceHandle;
  private boolean active;
  private int frameCount;


  private ByteBuffer vertexStagingBuffer;
  private long metalVertexBuffer;
  private int currentVertexCount;


  private MetalVertexConsumer metalVertexConsumer;


  private final List<EntityDrawCommand> pendingDraws = new ArrayList<>();
  private final ConcurrentLinkedQueue<CapturedEntity> capturedEntities =
      new ConcurrentLinkedQueue<>();


  private final java.util.Map<Integer, Long> textureCache =
      new java.util.concurrent.ConcurrentHashMap<>();


  private final MatrixStack matrixStack = new MatrixStack();

  public MetalEntityRenderer() {
    vertexStagingBuffer =
        ByteBuffer.allocateDirect(MAX_BATCH_VERTICES * ENTITY_VERTEX_STRIDE)
            .order(ByteOrder.nativeOrder());
    metalVertexConsumer =
        new MetalVertexConsumer(vertexStagingBuffer, MAX_BATCH_VERTICES);
  }


  public void setDeviceAndPipeline(long device, long pipeline) {
    this.deviceHandle = device;

    if (device != 0) {
      metalVertexBuffer = NativeBridge.nCreateBuffer(
          device, MAX_BATCH_VERTICES * ENTITY_VERTEX_STRIDE, 0);
      MetalLogger.info("MetalEntityRenderer initialized: device=%d vb=%d",
                       device, metalVertexBuffer);
    }
  }

  public void setActive(boolean active) { this.active = active; }

  public boolean isActive() { return active; }


  public void captureEntity(Entity entity, float tickDelta,
                            Matrix4f modelMatrix) {
    if (!active || entity == null)
      return;
    if (capturedEntities.size() >= MAX_ENTITIES_PER_FRAME)
      return;

    CapturedEntity captured = new CapturedEntity();
    captured.entity = entity;
    captured.tickDelta = tickDelta;
    captured.modelMatrix = new Matrix4f(modelMatrix);
    captured.isHurt = entity instanceof LivingEntity le && le.hurtTime > 0;
    captured.hurtFactor =
        captured.isHurt ? ((LivingEntity)entity).hurtTime / 10.0f : 0.0f;

    capturedEntities.add(captured);
  }


  @SuppressWarnings("unchecked")
  public void buildEntityMeshes(long frameContext) {
    if (!active || frameContext == 0 || deviceHandle == 0)
      return;

    metalVertexConsumer.reset();
    currentVertexCount = 0;
    pendingDraws.clear();

    MinecraftClient client = MinecraftClient.getInstance();
    if (client == null || client.world == null)
      return;

    EntityRenderManager renderManager = client.getEntityRenderDispatcher();
    if (renderManager == null)
      return;

    double camX = CapturedMatrices.isValid() ? CapturedMatrices.getCamX() : 0;
    double camY = CapturedMatrices.isValid() ? CapturedMatrices.getCamY() : 0;
    double camZ = CapturedMatrices.isValid() ? CapturedMatrices.getCamZ() : 0;

    int entitiesRendered = 0;
    int modelCaptures = 0;
    int boxFallbacks = 0;

    while (!capturedEntities.isEmpty()) {
      CapturedEntity captured = capturedEntities.poll();
      if (captured == null)
        break;

      Entity entity = captured.entity;
      if (entity == null || !entity.isAlive())
        continue;

      int startVertex = metalVertexConsumer.getVertexCount();
      boolean usedModel = false;

      try {
        usedModel = renderEntityModel(entity, captured, renderManager, camX,
                                      camY, camZ);
      } catch (Exception e) {
        if (frameCount < 5) {
          MetalLogger.warn("Failed to render entity model for %s: %s",
                           entity.getType().toString(), e.getMessage());
        }
      }


      if (!usedModel || metalVertexConsumer.getVertexCount() == startVertex) {
        buildEntityQuads(entity, captured, camX, camY, camZ);
        boxFallbacks++;
      } else {
        modelCaptures++;
      }

      int verticesAdded = metalVertexConsumer.getVertexCount() - startVertex;

      if (verticesAdded > 0) {
        EntityDrawCommand cmd = new EntityDrawCommand();
        cmd.startVertex = startVertex;
        cmd.vertexCount = verticesAdded;
        cmd.hurtFactor = captured.hurtFactor;
        cmd.whiteFlash = 0.0f;
        cmd.renderFlags = 0;
        cmd.glTextureId = captured.glTextureId;
        pendingDraws.add(cmd);
        entitiesRendered++;
      }
    }

    currentVertexCount = metalVertexConsumer.getVertexCount();

    if (frameCount < 5 && entitiesRendered > 0) {
      MetalLogger.info(
          "buildEntityMeshes: %d entities (%d model, %d box) -> %d verts",
          entitiesRendered, modelCaptures, boxFallbacks, currentVertexCount);
    }
  }


  @SuppressWarnings({"unchecked", "rawtypes"})
  private boolean renderEntityModel(Entity entity, CapturedEntity captured,
                                    EntityRenderManager renderManager,
                                    double camX, double camY, double camZ) {
    @SuppressWarnings("unchecked")
    EntityRenderer renderer = (EntityRenderer)renderManager.getRenderer(entity);
    if (renderer == null)
      return false;


    if (!(renderer instanceof LivingEntityRenderer livingRenderer))
      return false;


    EntityRenderState state;
    try {
      state = (EntityRenderState)livingRenderer.getAndUpdateRenderState(
          entity, captured.tickDelta);
    } catch (Exception e) {
      return false;
    }
    if (state == null)
      return false;


    matrixStack.loadIdentity();

    double ex =
        MathHelper.lerp(captured.tickDelta, entity.lastX, entity.getX()) - camX;
    double ey =
        MathHelper.lerp(captured.tickDelta, entity.lastY, entity.getY()) - camY;
    double ez =
        MathHelper.lerp(captured.tickDelta, entity.lastZ, entity.getZ()) - camZ;


    Vec3d offset = renderer.getPositionOffset(state);
    ex += offset.x;
    ey += offset.y;
    ez += offset.z;

    matrixStack.translate((float)ex, (float)ey, (float)ez);


    if (state instanceof LivingEntityRenderState livingState) {
      float bodyYaw = livingState.bodyYaw;
      matrixStack.multiply(RotationAxis.NEGATIVE_Y.rotationDegrees(bodyYaw));
    }



    matrixStack.translate(0.0f, 1.501f, 0.0f);
    matrixStack.scale(-1.0f, -1.0f, 1.0f);


    Model model = livingRenderer.getModel();
    if (!(model instanceof EntityModel entityModel))
      return false;

    try {
      entityModel.setAngles(state);
    } catch (Exception e) {

    }




    int light = state.light;
    if (light == 0) {
      light = 0x00F000F0;
    }



    model.render(matrixStack, metalVertexConsumer, light,
                 OverlayTexture.DEFAULT_UV);


    try {
      if (state instanceof LivingEntityRenderState livingState) {
        @SuppressWarnings("unchecked")
        LivingEntityRenderer<?, LivingEntityRenderState, ?> typedRenderer =
            (LivingEntityRenderer<?, LivingEntityRenderState, ?>) livingRenderer;
        Identifier texId = typedRenderer.getTexture(livingState);
        if (texId != null) {
          AbstractTexture mcTex =
              MinecraftClient.getInstance().getTextureManager().getTexture(
                  texId);
          if (mcTex != null) {
            com.mojang.blaze3d.textures.GpuTexture gpuTex =
                mcTex.getGlTexture();
            if (gpuTex instanceof GlTexture glTex) {
              captured.glTextureId = glTex.getGlId();
            }
          }
        }
      }
    } catch (Exception e) {

    }

    return true;
  }


  private void buildEntityQuads(Entity entity, CapturedEntity captured,
                                double camX, double camY, double camZ) {
    float tickDelta = captured.tickDelta;
    float ex =
        (float)(MathHelper.lerp(tickDelta, entity.lastX, entity.getX()) - camX);
    float ey =
        (float)(MathHelper.lerp(tickDelta, entity.lastY, entity.getY()) - camY);
    float ez =
        (float)(MathHelper.lerp(tickDelta, entity.lastZ, entity.getZ()) - camZ);

    float halfW = entity.getWidth() * 0.5f;
    float height = entity.getHeight();

    float x0 = ex - halfW, y0 = ey, z0 = ez - halfW;
    float x1 = ex + halfW, y1 = ey + height, z1 = ez + halfW;

    int color = 0xFFFFFFFF;
    if (captured.isHurt) {
      int gb = (int)(255 * (1.0f - captured.hurtFactor * 0.6f));
      color = (255 << 24) | (255 << 16) | (gb << 8) | gb;
    }
    int light = 0x00F000F0;


    emitQuad(x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, 0, 0, 1, color,
             light);

    emitQuad(x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0, 0, 0, -1, color,
             light);

    emitQuad(x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, 0, 1, 0, color,
             light);

    emitQuad(x0, y0, z1, x0, y0, z0, x1, y0, z0, x1, y0, z1, 0, -1, 0, color,
             light);

    emitQuad(x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1, 1, 0, 0, color,
             light);

    emitQuad(x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, -1, 0, 0, color,
             light);
  }


  private void emitQuad(float x0, float y0, float z0, float x1, float y1,
                        float z1, float x2, float y2, float z2, float x3,
                        float y3, float z3, float nx, float ny, float nz,
                        int color, int light) {

    metalVertexConsumer.vertex(x0, y0, z0, color, 0, 0, 0, light, nx, ny, nz);
    metalVertexConsumer.vertex(x1, y1, z1, color, 1, 0, 0, light, nx, ny, nz);
    metalVertexConsumer.vertex(x2, y2, z2, color, 1, 1, 0, light, nx, ny, nz);
    metalVertexConsumer.vertex(x3, y3, z3, color, 0, 1, 0, light, nx, ny, nz);
  }


  public void renderCapturedEntities(long frameContext) {
    if (!active || frameContext == 0 || deviceHandle == 0)
      return;

    buildEntityMeshes(frameContext);

    if (currentVertexCount == 0 || pendingDraws.isEmpty())
      return;


    vertexStagingBuffer.flip();
    int uploadSize = currentVertexCount * ENTITY_VERTEX_STRIDE;

    if (metalVertexBuffer != 0 && uploadSize > 0) {
      byte[] data = new byte[uploadSize];
      vertexStagingBuffer.get(data);
      NativeBridge.nUploadBufferData(metalVertexBuffer, data, 0, uploadSize);
    }


    MetalRenderer renderer = MetalRenderClient.getRenderer();
    if (renderer == null) {
      if (frameCount < 5)
        MetalLogger.warn("MetalEntityRenderer: renderer null");
      return;
    }


    long entityPipeline =
        NativeBridge.nGetEntityPipelineHandle(renderer.getHandle());
    if (entityPipeline != 0) {
      NativeBridge.nSetPipelineState(frameContext, entityPipeline);
      if (frameCount < 3) {
        MetalLogger.info("Using entity pipeline: %d", entityPipeline);
      }
    } else {
      long inhousePipeline = renderer.getBackend().getInhousePipelineHandle();
      if (inhousePipeline == 0) {
        if (frameCount < 5)
          MetalLogger.warn("MetalEntityRenderer: no pipeline available");
      } else {
        NativeBridge.nSetPipelineState(frameContext, inhousePipeline);
        if (frameCount < 3) {
          MetalLogger.warn("FALLING BACK to terrain pipeline for entities! " +
                           "entityPipeline=0");
        }
      }
    }


    NativeBridge.nSetChunkOffset(frameContext, 0.0f, 0.0f, 0.0f);


    int drawsDone = 0;
    for (EntityDrawCommand cmd : pendingDraws) {
      if (cmd.vertexCount <= 0)
        continue;


      NativeBridge.nSetEntityOverlay(frameContext, cmd.hurtFactor,
                                     cmd.whiteFlash, 1.0f);


      if (cmd.glTextureId != 0) {
        long metalTex = getOrCreateMetalTexture(cmd.glTextureId);
        if (metalTex != 0) {
          NativeBridge.nBindEntityTexture(frameContext, metalTex);
        }
        if (frameCount < 3) {
          MetalLogger.info("Entity draw: glTexId=%d metalTex=%d verts=%d",
                           cmd.glTextureId, metalTex, cmd.vertexCount);
        }
      } else {
        if (frameCount < 3) {
          MetalLogger.info("Entity draw: NO texture, verts=%d",
                           cmd.vertexCount);
        }
      }


      NativeBridge.nDrawEntityBuffer(frameContext, metalVertexBuffer,
                                     cmd.vertexCount, cmd.startVertex,
                                     cmd.renderFlags);
      drawsDone++;
    }

    frameCount++;
    if (frameCount <= 5 || frameCount % 500 == 0) {
      MetalLogger.info(
          "MetalEntityRenderer: frame %d, %d entities, %d verts, %d draws",
          frameCount, pendingDraws.size(), currentVertexCount, drawsDone);
    }

    pendingDraws.clear();
    capturedEntities.clear();
  }


  private long getOrCreateMetalTexture(int glTextureId) {
    if (glTextureId == 0 || deviceHandle == 0)
      return 0;

    Long cached = textureCache.get(glTextureId);
    if (cached != null)
      return cached;

    try {
      int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, glTextureId);

      int width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0,
                                               GL11.GL_TEXTURE_WIDTH);
      int height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0,
                                                GL11.GL_TEXTURE_HEIGHT);

      if (width <= 0 || height <= 0 || width > 4096 || height > 4096) {
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

      long metalTex =
          NativeBridge.nCreateTexture2D(deviceHandle, width, height, pixelData);
      textureCache.put(glTextureId, metalTex);

      if (metalTex != 0 && frameCount < 2) {

        int nonWhite = 0;
        int nonBlack = 0;
        int transparent = 0;
        int sampleCount = Math.min(64, pixelData.length / 4);
        for (int i = 0; i < sampleCount; i++) {
          int r = pixelData[i * 4] & 0xFF;
          int g = pixelData[i * 4 + 1] & 0xFF;
          int b = pixelData[i * 4 + 2] & 0xFF;
          int a = pixelData[i * 4 + 3] & 0xFF;
          if (r < 250 || g < 250 || b < 250)
            nonWhite++;
          if (r > 5 || g > 5 || b > 5)
            nonBlack++;
          if (a < 128)
            transparent++;
        }
        MetalLogger.info(
            "Created Metal entity texture: glId=%d size=%dx%d metalHandle=%d " +
            "pixels(first%d): nonWhite=%d nonBlack=%d transparent=%d",
            glTextureId, width, height, metalTex, sampleCount, nonWhite,
            nonBlack, transparent);
      }
      return metalTex;
    } catch (Exception e) {
      MetalLogger.error("Failed to create Metal entity texture for glId=%d: %s",
                        glTextureId, e.getMessage());
      textureCache.put(glTextureId, 0L);
      return 0;
    }
  }

  public void invalidateTextureCache() {
    textureCache.clear();
    MetalLogger.info("Entity texture cache invalidated");
  }

  public int getLastEntityCount() { return pendingDraws.size(); }

  public int getLastVertexCount() { return currentVertexCount; }

  public void shutdown() {
    active = false;
    capturedEntities.clear();
    pendingDraws.clear();
    textureCache.clear();

    if (metalVertexBuffer != 0) {
      NativeBridge.nDestroyBuffer(metalVertexBuffer);
      metalVertexBuffer = 0;
    }

    deviceHandle = 0;
    MetalLogger.info("MetalEntityRenderer shut down");
  }





  private static class CapturedEntity {
    Entity entity;
    float tickDelta;
    Matrix4f modelMatrix;
    boolean isHurt;
    float hurtFactor;
    int glTextureId;
  }

  private static class EntityDrawCommand {
    int startVertex;
    int vertexCount;
    float hurtFactor;
    float whiteFlash;
    int renderFlags;
    int glTextureId;
  }
}
