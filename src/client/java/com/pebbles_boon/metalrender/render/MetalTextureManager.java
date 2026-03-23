package com.pebbles_boon.metalrender.render;
import com.pebbles_boon.metalrender.nativebridge.NativeBridge;
import com.pebbles_boon.metalrender.util.MetalLogger;
import java.nio.ByteBuffer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.texture.GlTexture;
import net.minecraft.util.Identifier;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
public class MetalTextureManager {
  private static final Identifier BLOCKS_ATLAS_ID = Identifier.of("minecraft", "textures/atlas/blocks.png");
  private final long deviceHandle;
  private long blockAtlasTexture;
  private long lightmapTexture;
  private boolean blockAtlasLoaded;
  private boolean lightmapLoaded;
  private boolean usingFallbackBlockAtlas;
  private int blockAtlasWidth;
  private int blockAtlasHeight;
  public MetalTextureManager(long deviceHandle) {
    this.deviceHandle = deviceHandle;
  }
  public void loadBlockAtlas() {
    try {
      MinecraftClient mc = MinecraftClient.getInstance();
      if (mc == null || mc.getTextureManager() == null)
        return;
      AbstractTexture atlasTexture = mc.getTextureManager().getTexture(BLOCKS_ATLAS_ID);
      if (atlasTexture == null) {
        MetalLogger.info("Block atlas texture not available yet");
        blockAtlasLoaded = true;
        usingFallbackBlockAtlas = true;
        return;
      }
      int glTexId = 0;
      var gpuTex = atlasTexture.getGlTexture();
      if (gpuTex instanceof GlTexture glTex) {
        glTexId = glTex.getGlId();
      }
      if (glTexId == 0) {
        MetalLogger.info("Block atlas GL texture ID is 0");
        blockAtlasLoaded = true;
        usingFallbackBlockAtlas = true;
        return;
      }
      int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, glTexId);
      int width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0,
          GL11.GL_TEXTURE_WIDTH);
      int height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0,
          GL11.GL_TEXTURE_HEIGHT);
      if (width <= 0 || height <= 0) {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
        MetalLogger.info("Block atlas dimensions invalid: %dx%d", width,
            height);
        blockAtlasLoaded = true;
        usingFallbackBlockAtlas = true;
        return;
      }
      ByteBuffer pixels = BufferUtils.createByteBuffer(width * height * 4);
      GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA,
          GL11.GL_UNSIGNED_BYTE, pixels);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
      byte[] data = new byte[width * height * 4];
      pixels.get(data);
      long newTexture = NativeBridge.nCreateTexture2D(deviceHandle, width, height, data);
      if (newTexture != 0) {
        if (blockAtlasTexture != 0 && blockAtlasTexture != newTexture) {
        }
        blockAtlasTexture = newTexture;
        blockAtlasWidth = width;
        blockAtlasHeight = height;
        blockAtlasLoaded = true;
        usingFallbackBlockAtlas = false;
        MetalLogger.info("Block atlas loaded: %dx%d, Metal handle=%d", width,
            height, newTexture);
      } else {
        MetalLogger.error("Failed to create Metal texture for block atlas");
        blockAtlasLoaded = true;
        usingFallbackBlockAtlas = true;
      }
    } catch (Exception e) {
      MetalLogger.error("Error loading block atlas: %s", e.getMessage());
      blockAtlasLoaded = true;
      usingFallbackBlockAtlas = true;
    }
  }
  public void updateBlockAtlas() {
    if (blockAtlasTexture == 0 || usingFallbackBlockAtlas)
      return;
    try {
      MinecraftClient mc = MinecraftClient.getInstance();
      if (mc == null || mc.getTextureManager() == null)
        return;
      AbstractTexture atlasTexture = mc.getTextureManager().getTexture(BLOCKS_ATLAS_ID);
      if (atlasTexture == null)
        return;
      int glTexId = 0;
      var gpuTex = atlasTexture.getGlTexture();
      if (gpuTex instanceof GlTexture glTex) {
        glTexId = glTex.getGlId();
      }
      if (glTexId == 0)
        return;
      int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, glTexId);
      int width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0,
          GL11.GL_TEXTURE_WIDTH);
      int height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0,
          GL11.GL_TEXTURE_HEIGHT);
      if (width != blockAtlasWidth || height != blockAtlasHeight) {
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
        loadBlockAtlas();
        return;
      }
      ByteBuffer pixels = BufferUtils.createByteBuffer(width * height * 4);
      GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA,
          GL11.GL_UNSIGNED_BYTE, pixels);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
      byte[] data = new byte[width * height * 4];
      pixels.get(data);
      NativeBridge.nUpdateTexture2D(blockAtlasTexture, width, height, data);
    } catch (Exception e) {
    }
  }
  public void loadLightmap() {
    lightmapLoaded = true;
  }
  public boolean isBlockAtlasLoaded() {
    return blockAtlasLoaded;
  }
  public boolean isLightmapLoaded() {
    return lightmapLoaded;
  }
  public boolean isUsingFallbackBlockAtlas() {
    return usingFallbackBlockAtlas;
  }
  public long getBlockAtlasTexture() {
    return blockAtlasTexture;
  }
  public long getLightmapTexture() {
    return lightmapTexture;
  }
  public void destroy() {
    blockAtlasLoaded = false;
    lightmapLoaded = false;
    blockAtlasTexture = 0;
    lightmapTexture = 0;
  }
}
