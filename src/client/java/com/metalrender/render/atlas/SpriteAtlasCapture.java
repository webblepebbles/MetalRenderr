package com.metalrender.render.atlas;

import com.metalrender.sodium.mixins.accessor.AbstractTextureAccessor;
import com.metalrender.sodium.mixins.accessor.GlTextureAccessor;
import com.metalrender.sodium.mixins.accessor.SpriteAtlasTextureAccessor;
import com.metalrender.sodium.mixins.accessor.SpriteContentsAccessor;
import com.metalrender.util.MetalLogger;
import com.mojang.blaze3d.textures.GpuTexture;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.texture.GlTexture;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.client.texture.SpriteContents;
import net.minecraft.util.Identifier;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.system.MemoryUtil;

public final class SpriteAtlasCapture {
  private SpriteAtlasCapture() {
  }

  public static Optional<CapturedAtlas> capture(SpriteAtlasTexture texture) {
    if (!(texture instanceof SpriteAtlasTextureAccessor accessor)) {
      MetalLogger.warn("[AtlasCapture] SpriteAtlasTexture missing accessor bridge");
      return Optional.empty();
    }

    int width = accessor.metalrender$getAtlasWidth();
    int height = accessor.metalrender$getAtlasHeight();
    if (width <= 0 || height <= 0) {
      MetalLogger.warn("[AtlasCapture] Invalid atlas size: {}x{}", width, height);
      return Optional.empty();
    }
    try {
      AbstractTexture abstractTex = MinecraftClient.getInstance().getTextureManager()
          .getTexture(Identifier.of("minecraft", "textures/atlas/blocks.png"));
      int glId = -1;
      if (abstractTex instanceof AbstractTextureAccessor texAccessor) {
        GpuTexture gpuTex = texAccessor.metalrender$getGlTexture();
        if (gpuTex instanceof GlTextureAccessor glTexAccessor) {
          glId = glTexAccessor.metalrender$getGlId();
        }
      }
      if (glId > 0) {
        Optional<CapturedAtlas> fromGl = captureFromOpenGL(glId, width, height);
        if (fromGl.isPresent()) {
          MetalLogger.info("[AtlasCapture] Captured atlas from OpenGL texture ({}x{})", width, height);
          return fromGl;
        }
      }
    } catch (Exception e) {
      MetalLogger.warn("[AtlasCapture] Failed to capture from OpenGL, falling back to sprite assembly: {}",
          e.getMessage());
    }

    Map<Identifier, Sprite> sprites = accessor.metalrender$getSprites();
    if (sprites == null || sprites.isEmpty()) {
      MetalLogger.warn("[AtlasCapture] No sprites available for fallback assembly");
      return Optional.empty();
    }

    try (NativeImage assembled = new NativeImage(NativeImage.Format.RGBA, width, height, false)) {
      for (Sprite sprite : sprites.values()) {
        blitSpriteIntoAtlas(assembled, sprite, width, height);
      }
      byte[] data = copyToBgra(assembled);
      MetalLogger.info("[AtlasCapture] Captured atlas via sprite assembly ({}x{})", width, height);
      return Optional.of(new CapturedAtlas(data, width, height));
    } catch (Exception e) {
      MetalLogger.error("[AtlasCapture] Failed to capture sprite atlas", e);
      return Optional.empty();
    }
  }

  
  private static Optional<CapturedAtlas> captureFromOpenGL(int glId, int width, int height) {
    if (glId <= 0 || width <= 0 || height <= 0) {
      return Optional.empty();
    }

    int prevTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);

    ByteBuffer buffer = null;
    try {
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, glId);

      int dataSize = width * height * 4;
      buffer = MemoryUtil.memAlloc(dataSize);

      GL12.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, buffer);

      int error = GL11.glGetError();
      if (error != GL11.GL_NO_ERROR) {
        MetalLogger.warn("[AtlasCapture] OpenGL error reading texture: {}", error);
        return Optional.empty();
      }

      byte[] data = new byte[dataSize];
      buffer.get(data);

      return Optional.of(new CapturedAtlas(data, width, height));
    } finally {
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTexture);

      if (buffer != null) {
        MemoryUtil.memFree(buffer);
      }
    }
  }

  public static byte[] copyToBgra(NativeImage image) {
    if (image == null) {
      return new byte[0];
    }
    int width = image.getWidth();
    int height = image.getHeight();
    byte[] data = new byte[width * height * 4];
    int index = 0;
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        int argb = image.getColorArgb(x, y);
        int a = (argb >>> 24) & 0xFF;
        int r = (argb >>> 16) & 0xFF;
        int g = (argb >>> 8) & 0xFF;
        int b = argb & 0xFF;
        data[index++] = (byte) b;
        data[index++] = (byte) g;
        data[index++] = (byte) r;
        data[index++] = (byte) a;
      }
    }
    return data;
  }

  private static void blitSpriteIntoAtlas(NativeImage target, Sprite sprite,
      int atlasWidth, int atlasHeight) {
    if (target == null || sprite == null) {
      return;
    }

    SpriteContents contents = sprite.getContents();
    if (contents == null) {
      return;
    }

    NativeImage[] mipmaps = ((SpriteContentsAccessor) (Object) contents)
        .metalrender$getMipmapImages();
    if (mipmaps == null || mipmaps.length == 0) {
      return;
    }

    NativeImage source = mipmaps[0];
    if (source == null) {
      return;
    }

    blitNativeImage(target, source, sprite.getX(), sprite.getY(), atlasWidth,
        atlasHeight);
  }

  private static void blitNativeImage(NativeImage target, NativeImage source,
      int dstX, int dstY, int atlasWidth, int atlasHeight) {
    if (target == null || source == null) {
      return;
    }

    int width = Math.min(source.getWidth(), atlasWidth - dstX);
    int height = Math.min(source.getHeight(), atlasHeight - dstY);
    if (width <= 0 || height <= 0) {
      return;
    }

    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        int argb = source.getColorArgb(x, y);
        target.setColorArgb(dstX + x, dstY + y, argb);
      }
    }
  }

}
