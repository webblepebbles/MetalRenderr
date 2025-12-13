package com.metalrender.render.atlas;

import com.metalrender.sodium.mixins.accessor.SpriteAtlasTextureAccessor;
import com.metalrender.sodium.mixins.accessor.SpriteContentsAccessor;
import com.metalrender.util.MetalLogger;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.client.texture.SpriteContents;
import net.minecraft.util.Identifier;

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
    Map<Identifier, Sprite> sprites = accessor.metalrender$getSprites();
    if (width <= 0 || height <= 0 || sprites == null || sprites.isEmpty()) {
      MetalLogger.warn(
          "[AtlasCapture] Invalid atlas metadata (size={}x{}, sprites={})", width,
          height, sprites == null ? "null" : sprites.size());
      return Optional.empty();
    }

    try (NativeImage assembled = new NativeImage(NativeImage.Format.RGBA, width, height, false)) {
      for (Sprite sprite : sprites.values()) {
        blitSpriteIntoAtlas(assembled, sprite, width, height);
      }
      byte[] data = copyToBgra(assembled);
      return Optional.of(new CapturedAtlas(data, width, height));
    } catch (Exception e) {
      MetalLogger.error("[AtlasCapture] Failed to capture sprite atlas", e);
      return Optional.empty();
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

  // Removed debug filename helper; writing capture to disk is disabled.
}
